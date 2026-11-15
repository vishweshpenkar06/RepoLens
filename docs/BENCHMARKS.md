# Benchmarks

All numbers below are real runs on the development machine, not estimates. Provenance: the owner
delegated HUMAN CHECKPOINT 5 (benchmark interpretation) to the agent; this interpretation is
agent-written (ADR-0016).

## Environment

| Item | Value |
|---|---|
| CPU | 12th Gen Intel Core i7-12700H, 14 cores / 20 threads |
| RAM | 15 GiB |
| OS | Nobara Linux 44 (Fedora 44 base), kernel 7.2 |
| JDK | OpenJDK 25.0.4.1 |
| App | Spring Boot 4.1.2, same host as k6 |
| Database | PostgreSQL 17 + pgvector 0.8.7 in Docker (host port 55432) |
| Dataset | `octocat/Hello-World`: 301 documents (README + 300 issues), 319 chunks, 384-dim MiniLM |
| LLM | deterministic fake, 300 ms simulated latency for `/ask` runs |
| Load tool | k6 v2.4.0 |
| API key / rate limits | key required; rate limiting disabled for load measurement |

Start command (virtual threads):

```bash
REPOLENS_API_KEY=loadtest-key RATE_LIMIT_ENABLED=false LLM_PROVIDER=fake \
  java -jar target/repolens-0.0.1-SNAPSHOT.jar
```

k6 commands:

```bash
k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=1 \
  --stage 5s:100 --stage 15s:200 --stage 5s:0 load/search.js

k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=1 \
  -e VUS=20 -e DURATION=20s -e MODE=fixed|unique load/ask_stubbed.js
```

## 1. Virtual threads vs platform threads (search, 200 VUs)

| Run | Request rate | p95 | p99 (from k6 max/p95 pattern) | Errors |
|---|---|---|---|---|
| Virtual threads (run 1) | 273.8 req/s | 803.8 ms | — | 0 |
| Virtual threads (run 2) | 273.5 req/s | 664.0 ms | — | 0 |
| Platform threads | 278.5 req/s | 690.2 ms | — | 0 |

**Interpretation.** Throughput is effectively identical; the difference is inside run-to-run noise
(±2%). This workload is DB-bound: every search issues a pgvector HNSW query plus a Postgres FTS
query, and the Hikari pool defaults to 10 connections. Once those 10 connections are busy, extra
carrier threads do not add throughput. Virtual threads did not help here, and the platform-thread
run shows the same p95. The benefit of virtual threads in RepoLens is expected on the `/ask` path
and ingestion, where requests block on LLM or HTTP I/O rather than on the DB pool; that is not what
this benchmark isolates. Raising the Hikari pool size (and measuring Postgres CPU) is the next
experiment, not adding threads.

## 2. Answer cache on/off (`/ask`, fake LLM 300 ms, 20 VUs, 20 s)

| Mode | Throughput | p95 | Errors |
|---|---|---|---|
| Cache hit (`MODE=fixed`, repeated question) | 808.5 req/s | 6.0 ms | 0 |
| Cache bypass (`MODE=unique`, unique question each iteration) | 53.1 req/s | 399.7 ms | 0 |

**Interpretation.** Caching is the single biggest latency lever: about **15x throughput** and
**~67x lower p95** for repeated questions. The uncached path is dominated by the simulated 300 ms
LLM call plus retrieval; 53 req/s at 20 VUs matches roughly 1/0.35 s per virtual user. The cache
key includes `contentVersion`, so the cheap path never serves answers across a re-index.

## 3. Retrieval latency breakdown (search load, 6,815 samples)

| Stage | p50 | p95 | p99 |
|---|---|---|---|
| Vector (pgvector HNSW) | 89.5 ms | 156.6 ms | 201.3 ms |
| Keyword (Postgres FTS) | 61.5 ms | 134.2 ms | 179.0 ms |
| RRF fuse | ≤1 ms | ≤1 ms | ≤1 ms |

End-to-end search p95 under a separate 100-VU run was 338 ms. Fusion is negligible; the database
queries dominate and run in parallel, so the slower of the two (vector, here) sets the floor. The
HNSW index has only ~319 vectors in this dataset; latency is mostly connection/query overhead, not
ANN search cost.

## 4. Ingestion throughput (Hello-World, 301 docs, 319 chunks)

| Embedding concurrency | Job duration | Throughput |
|---|---|---|
| 2 (default) | 12.03 s | 25.0 docs/s, 26.5 chunks/s |
| 8 | 11.15 s | 27.0 docs/s, 28.6 chunks/s |

**Interpretation.** Increasing embedding concurrency by 4x improved ingestion time by only ~7%.
For this repository the bottleneck is sequential GitHub REST fetching and per-document DB
transactions, not ONNX inference; 319 chunks is small. The semaphore still matters for CPU-bound
safety and should show gains on repositories with large `docs/` trees where many documents are
embedded per batch.

## Caveats

- Single runs on a shared desktop with other containers (Grafana, Prometheus, Postgres) running;
  noise is real. Treat ±10% as noise, not signal.
- The LLM is fake by design: these benchmarks measure RepoLens, not a provider.
- Rate limiting was disabled to measure capacity; production limits 429 long before these rates.
- `/search` results include snippets, so response serialization is part of the measured cost.
