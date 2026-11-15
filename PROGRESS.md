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

**Next:** Phase 2 retrieval, including HUMAN CHECKPOINT 2 (RRF stub/tests for the human).

**Known issues:** HUMAN CHECKPOINT 1 sections in `docs/ARCHITECTURE.md` are still human-owned TODOs.
Issue ingestion is capped at 300 by design.
