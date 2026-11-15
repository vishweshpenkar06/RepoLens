# Progress

## Phase 0 — Foundations (complete)

- Environment verified; JDK 25 installed, Docker group fixed (`docs/ENVIRONMENT.md`).
- Maven wrapper (Maven 3.10.0), Spring Boot 4.1.2 skeleton, package layout.
- Spotless + JaCoCo wired into `verify`; typed configuration records.
- Flyway `V1` schema (pgvector, FTS, HNSW) with Testcontainers migration tests.
- `docker-compose.yml`: Postgres+pgvector, Prometheus, Grafana; host ports remapped.
- GitHub Actions CI: build + tests + Testcontainers + coverage artifact.
- Docs skeleton: README, ARCHITECTURE, DECISIONS, BACKLOG, ENVIRONMENT.

## Phase 1 — Ingestion (complete)

- `RestGitHubClient`: tree/contents/issues, ETag 304, rate-limit backoff, optional token, no redirects.
- `SsrfGuard`: github.com only, public-IP resolution, rejects credentials/ports/query/fragment.
- `MarkdownChunker`: heading paths, code fences, tables, badge/HTML cleanup, 500/800/12% rules.
- `OnnxEmbedder`: local all-MiniLM-L6-v2 (384 dims), startup warmup, readiness indicator.
- `IngestionService` + virtual-thread job runner: batched embeddings with semaphore, transactional
  per-document writes, incremental re-ingest, deletion of removed docs, content version.
- REST: `POST /api/v1/repos`, `GET /api/v1/repos`, `GET /api/v1/repos/{id}`,
  `POST /api/v1/repos/{id}/reindex`; RFC 7807 errors.
- 83 tests; chunker line coverage 98.5%. Real run: `octocat/Hello-World` (301 docs: README + 300
  issues, 319 chunks, 384 dims); re-index wrote 0 chunks in ~0.7 s.

## Phase 2 — Retrieval (complete)

- `VectorSearch` (pgvector cosine) and `KeywordSearch` (FTS `ts_rank_cd`) with repo filtering,
  limits and source metadata.
- `RankFusion`: RRF with `k = 60`, deterministic tie-break on chunk id, input immutability.
  11 contract tests run in the default build.
- `Retriever`: parallel vector + keyword search on virtual threads, 15 s stage timeout, limit
  clamp 1..12.
- `GET /api/v1/repos/{id}/search?q=&k=` with RFC 7807 errors (400 invalid query, 404 repo, 503
  retrieval failure); snippet + both ranks + fused score.
- 113 tests green, coverage gates met. Real check on `octocat/Hello-World`: `hello` returns the
  README (vector #2, keyword #4) plus issue hits; `fork` returns vector-only hits, proving both
  branches and fusion are live.

**Checkpoint provenance:** the owner delegated HUMAN CHECKPOINT 1 (design write-up) and
HUMAN CHECKPOINT 2 (RRF) to the agent on 2026-11-15. Both are agent-written; see ADR-0016. Do not
present them as human-written unless the owner reviews and can explain them.

**Next:** Phase 3 answering (prompt builder, LLM gateway, citations, SSE, caching), then Phase 4.

**Known issues:**
- Issue ingestion is capped at 300 by design.
- Vector search always returns nearest neighbours, so a query with no lexical match still returns
  semantic candidates; the eval set must account for this.
