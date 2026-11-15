# RepoLens

Self-hosted Java service that ingests a GitHub repository's documentation and issues, then answers
questions about it with verified citations via a REST API and an MCP server for AI assistants.

> Status: Phase 0 (foundations). Ingestion, retrieval, answering, and MCP land phase by phase.
> See `PROGRESS.md` and `MASTERPLAN.md`.

## Stack

Java 25 · Spring Boot 4.1.2 · Spring AI 2.0.2 · PostgreSQL 17 + pgvector 0.8.7 · Flyway ·
Caffeine · Micrometer/Prometheus/Grafana · Testcontainers · k6.

Exact versions and rationale: `docs/DECISIONS.md`.

## Quickstart

Prerequisites: JDK 21+ (25 recommended), Docker with the compose plugin.

```bash
cp .env.example .env
docker compose up -d
./mvnw verify          # build, tests (Testcontainers), Spotless, JaCoCo
./mvnw spring-boot:run # app on http://localhost:8081
curl localhost:8081/actuator/health
```

Local ports (non-default on purpose, see `docs/ENVIRONMENT.md`):

| Service | URL |
|---|---|
| App | http://localhost:8081 |
| Postgres | `localhost:55432` (`repolens`/`repolens`) |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 (admin/admin) |

## Documentation

- `docs/ARCHITECTURE.md` — components, data flow, failure modes
- `docs/DECISIONS.md` — ADRs
- `docs/ENVIRONMENT.md` — verified toolchain
- `docs/BACKLOG.md` — ideas outside the plan
- `MASTERPLAN.md` — the plan this project follows

## API (planned)

`/api/v1/repos`, `/repos/{id}/search`, `/repos/{id}/ask`, `/repos/{id}/ask/stream`, plus MCP tools
`list_repositories`, `search_docs`, `ask_repo`, `get_document`. All endpoints require `X-API-Key`.

## Results

Published after Phase 5 (evaluation + benchmarks). No numbers are claimed before measurement.

## License

TBD (MIT or Apache-2.0) in Phase 6.
