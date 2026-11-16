# Benchmarks

All numbers are real runs on the development machine. Provenance: the owner delegated HUMAN
CHECKPOINT 5 to the agent (ADR-0016). The interpretation below is agent-written.

## Machine and software

| Item | Value |
|---|---|
| CPU | 12th Gen Intel Core i7-12700H, 14 cores / 20 threads |
| RAM | 15 GiB |
| OS | Nobara Linux 44 (Fedora 44 base), kernel 7.2 |
| JDK | OpenJDK 25.0.4.1 (Temurin-compatible Red Hat build) |
| App | `repolens-0.1.0.jar`, Spring Boot 4.1.2 |
| Database | PostgreSQL 17 + pgvector 0.8.7 in Docker, host port 55432 |
| Dataset | `encode/httpx`: 967 chunks, 384-dim all-MiniLM-L6-v2 vectors |
| HTTP load | k6 v2.4.0, same host |
| LLM | deterministic fake (search does not call it) |
| Auth / limits | API key required, rate limiting disabled for capacity measurement |

The machine is shared with other work. Runs during a heavily loaded period (`load average` 40–60,
mostly unrelated Python/vision processes) are excluded; final numbers were taken after stopping an
unrelated `dinesense` stack and with the host near idle (~95% idle at start). Treat ±10% as noise.

## Commands

Search load (200 VUs, 25 s ramp):

```bash
REPOLENS_API_KEY=loadtest-key RATE_LIMIT_ENABLED=false LLM_PROVIDER=fake \
  java -jar target/repolens-0.1.0.jar   # add --spring.threads.virtual.enabled=false for platform threads

k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=4 \
  --stage 5s:100 --stage 15s:200 --stage 5s:0 load/search.js
```

Three independent runs per configuration; the median is reported. The repo and query set are
identical across all runs (`load/search.js` cycles seven fixed queries).

## Before / after: what was slow and what fixed it

Initial profile at 200 VUs:

- Postgres was ~**1,800% CPU (18 cores)**, the host ~97% busy, and **255 requests waited** for a
  DB connection. Hikari average acquire wait was **412–587 ms**; connection usage averaged ~50–65 ms.
- Only **10 Hikari connections** existed, while each search made 4 DB calls: API-key lookup,
  repository lookup, vector search, keyword search.
- Query embedding was **not** the cost: 7,221 cache hits took 0.06 s total; 9 misses took 0.17 s.
- Idle `/search` was ~50 ms. `EXPLAIN ANALYZE` showed vector search using a **sequential scan** of
  967 rows (2.4 ms), keyword FTS 0.7 ms. Forcing plain HNSW without iterative scan was *slower*
  (16 ms) and returned only 10 of 30 rows because of the repository filter.
- pgvector 0.8 **iterative HNSW** returned all 30 rows in **0.85 ms**, 3× faster than the seq scan
  and with much lower CPU.

Fixes applied:

1. **Cache API-key authentication** for 60 s (configurable, `REPOLENS_API_KEY_CACHE_TTL`; `0s`
   disables). Removes one DB query per request. Revocation therefore takes up to the TTL.
2. **Cache positive repository existence** for 10 min in `SearchService` (repositories cannot be
   deleted through the API). Removes another DB query.
3. **Configurable Hikari pool** (`DB_POOL_SIZE`, default 20; `DB_MIN_IDLE`, default 5).
4. **Dedicated vector pool** (`VECTOR_POOL_SIZE`, default 10) with
   `hnsw.iterative_scan=relaxed_order`, `enable_seqscan=off`, `enable_bitmapscan=off`. The main pool
   keeps the planner free for FTS bitmap scans. This was the largest win.
5. **Embedding latency metric** to prove the ONNX cache is not the bottleneck.

## Search: 200 virtual users, 25 s (3 runs each)

| Configuration | Median throughput | Throughput range | Median p95 | p95 range |
|---|---|---|---|---|
| Baseline: pool 10, no auth cache | 127.7 req/s | 120.8–155.0 | 1,527 ms | 1,220–1,542 |
| + auth cache, repo cache, pool 20 | 156.1 req/s | 155.4–172.9 | 1,152 ms | 1,152–1,168 |
| + dedicated iterative-HNSW vector pool | **2,281.5 req/s** | 2,272.0–2,282.3 | **4 ms** | 4–4 |
| Final, platform threads | **2,272.7 req/s** | 2,266.3–2,276.4 | **4–5 ms** | 4–5 |

The final throughput is limited by k6's intentional 50 ms think time per iteration (the theoretical
cap at 200 VUs is ~3,700 req/s), not by the service. p95 fell from 1.5 s to 4 ms.

### Retrieval stage latency after the fixes (170,516 samples)

| Stage | p50 | p95 | p99 |
|---|---|---|---|
| Vector (iterative HNSW) | 1.4 ms | 3.1 ms | 4.2 ms |
| Keyword (Postgres FTS) | 1.0 ms | 2.1 ms | 3.1 ms |
| RRF fuse | 1.0 ms | 1.0 ms | 1.0 ms |

Query embeddings: 170,509 cache hits in 0.097 s total; 7 cold misses in 0.031 s. ONNX inference is
not a factor for repeated queries.

Post-fix Hikari (main pool): average acquire **0.003 ms**, average usage **0.46 ms** — queueing is
gone. The vector workload runs on its own pool and no longer competes with FTS for connections.

## Virtual threads vs platform threads

- Baseline: 127.7 (virtual) vs 121.2 req/s (platform); p95 1,527 vs 1,530 ms.
- After fixes: 2,281.5 (virtual) vs 2,272.7 req/s (platform); p95 4 vs 4–5 ms.

**Virtual threads did not help `/search`.** It is DB-bound; virtual threads only change how the
request waits, not how fast Postgres answers, and the table shows the difference is inside noise.
Virtual threads are expected to matter for the LLM call path and ingestion (blocking I/O against
external services), not for a connection-pool-bound SQL endpoint.

## Other measurements (earlier runs, same machine)

- `/ask` with fake LLM 300 ms, 20 VUs: cache hit **808 req/s at p95 6 ms**; unique questions
  **53 req/s at p95 400 ms**. Caching is the dominant answer-path lever.
- Ingestion `octocat/Hello-World` (301 docs / 319 chunks): 12.03 s at embedding concurrency 2,
  11.15 s at concurrency 8. GitHub fetching dominates at this size.

## Caveats

- Shared desktop; final runs were taken near-idle but a root-owned `uvicorn` process still used CPU.
- One dataset (`encode/httpx`, 967 chunks). At this size the planner prefers seq scan; the HNSW
  pool is tuned for it. At 10k+ chunks, planner behavior and pool sizing must be re-measured.
- k6 and the service share the host, so absolute throughput is not a hardware-independent claim.
- Rate limiting was disabled to measure capacity; production limits requests earlier by design.
