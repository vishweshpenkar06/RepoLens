# RepoLens

Self-hosted Java service that ingests a GitHub repository's documentation and issues, then answers
questions about it with verified citations via a REST API and an MCP server for AI assistants.

> Status: Phase 4 (MCP + security). Observability, evaluation, and deployment land phase by phase.
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

## Authentication

All `/api/*` and `/mcp` requests need an API key. Set `REPOLENS_API_KEY` in `.env` before starting;
only its SHA-256 hash is stored. Example:

```bash
export REPOLENS_API_KEY=$(openssl rand -hex 32)
curl -s localhost:8081/api/v1/repos -H "X-API-Key: $REPOLENS_API_KEY"
```

## Try ingestion

```bash
# Register a repo (async). Returns 202 with repoId + jobId.
curl -s -X POST localhost:8081/api/v1/repos \
  -H "X-API-Key: $REPOLENS_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://github.com/octocat/Hello-World"}'

curl -s localhost:8081/api/v1/repos/1 -H "X-API-Key: $REPOLENS_API_KEY"
curl -s localhost:8081/api/v1/repos   -H "X-API-Key: $REPOLENS_API_KEY"

# Incremental re-index (ETag + content hash; unchanged docs are not re-embedded)
curl -s -X POST localhost:8081/api/v1/repos/1/reindex -H "X-API-Key: $REPOLENS_API_KEY"
```

Only `https://github.com/{owner}/{name}` is accepted. Ingestion covers the README, top-level
markdown, `docs/**/*.md`, and up to 300 issues (`GITHUB_ISSUES_ENABLED=false` to disable).
Embeddings are local ONNX (384 dims); the first run downloads the model (~90 MB) and caches it.

## Search

```bash
# Hybrid search: vector + full-text, fused with Reciprocal Rank Fusion (k = 60).
curl -s "localhost:8081/api/v1/repos/1/search?q=docker+compose&k=6" \
  -H "X-API-Key: $REPOLENS_API_KEY"
```

Each hit includes the snippet, source URL, fused score, and its 1-based rank in each retriever
(`null` when that retriever did not return it). `k` is clamped to 1..12.

## Ask

```bash
curl -s -X POST localhost:8081/api/v1/repos/1/ask \
  -H "X-API-Key: $REPOLENS_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"question":"How do I run this?","topK":6}'
# -> { answer, citations[], refused, latencyMs, cached }

curl -N "localhost:8081/api/v1/repos/1/ask/stream?question=How+do+I+run+this%3F&topK=6" \
  -H "X-API-Key: $REPOLENS_API_KEY"
```

Answers are grounded, cite sources, and refuse when evidence is missing. The default LLM is a
deterministic fake (no key). For a real model set any OpenAI-compatible endpoint:

```bash
LLM_PROVIDER=openai           # or openrouter, groq, ollama, openai-compatible...
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=...
LLM_MODEL=gpt-4o-mini
```

Ollama: `LLM_PROVIDER=ollama LLM_BASE_URL=http://localhost:11434/v1 LLM_MODEL=llama3.1`.

## MCP

Four tools are exposed over streamable HTTP (`/mcp`) and stdio. See `docs/MCP_SETUP.md` for the
Claude Desktop config and a generic client example. HTTP requests need `X-API-Key`.

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
