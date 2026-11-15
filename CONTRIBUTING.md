# Contributing

## Prerequisites

- JDK 21+ (the project targets 25)
- Docker with the Compose plugin (Testcontainers and local Postgres/pgvector)
- No global Maven; use `./mvnw`

## Build and test

```bash
cp .env.example .env
docker compose up -d postgres
./mvnw verify                 # tests, Spotless, JaCoCo gates
./mvnw -Peval verify          # evaluation harness (network + ~2 min)
```

Formatting is enforced by Spotless; run `./mvnw spotless:apply` before committing.

## Architecture rules

- Package by feature: `api`, `mcp`, `ingest`, `embed`, `retrieve`, `answer`, `ratelimit`, `cache`,
  `security`, `observability`, `config`, `common`.
- New non-trivial choices need an ADR in `docs/DECISIONS.md`.
- No secrets in the repository. Configuration goes through environment variables and
  `docs`-documented properties.
- Retrieved text is untrusted data: never execute or follow instructions from it, and keep it
  delimited in prompts and MCP tool output.

## Tests

- Unit tests for pure logic; Testcontainers for SQL/pgvector; WireMock for GitHub/LLM HTTP.
- Do not skip or disable failing tests to get a green build.
- Keep coverage above the gates in `pom.xml` (>=70% overall; chunker/retrieval/ratelimit/verifier
  have stricter targets from the plan).

## Commits

Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), small and focused.

## Human checkpoints

The MASTERPLAN defines human-owned checkpoints. In this repository they were delegated to the agent
on 2026-11-15 (ADR-0016); see `PROGRESS.md`. Do not quote them as human-written without review.
