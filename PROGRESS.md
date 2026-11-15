# Progress

## Phase 0 — Foundations (complete)

- Environment verified; JDK 25 installed, Docker group fixed (`docs/ENVIRONMENT.md`).
- Maven wrapper (Maven 3.10.0), Spring Boot 4.1.2 skeleton, package layout.
- Spotless + JaCoCo wired into `verify`; typed configuration records.
- Flyway `V1` schema (pgvector, FTS, HNSW) with Testcontainers migration tests.
- `docker-compose.yml`: Postgres+pgvector, Prometheus, Grafana; host ports remapped.
- GitHub Actions CI: build + tests + Testcontainers + coverage artifact.
- Docs skeleton: README, ARCHITECTURE, DECISIONS, BACKLOG, ENVIRONMENT.

**Next:** HUMAN CHECKPOINT 1 (design write-up), then Phase 1 ingestion.

**Known issues:** none.
