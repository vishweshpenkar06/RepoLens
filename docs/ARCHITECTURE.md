# Architecture

## Status

Phase 1 complete: skeleton, configuration, schema, REST repo endpoints, GitHub ingestion,
Markdown chunking, local ONNX embeddings, incremental re-index. Later phases add retrieval,
answering, MCP, security, and evaluation.

## Context

RepoLens ingests a GitHub repository's documentation and issues, then answers questions with
citations over REST and MCP. Retrieval is hybrid (pgvector + Postgres full-text search, fused with
Reciprocal Rank Fusion). Answers must be grounded, cited, and refuse when evidence is missing.

## Components (planned, per MASTERPLAN)

```
Client --> REST API (MVC, /api/v1/*)        MCP Server (streamable HTTP + stdio) <-- AI assistants
                    \                              /
                     +-------- Application layer --+
                     | AskService | SearchService | IngestionService |
                     +----+-----------+-----------+------------------+
                          |           |           |
                    GitHubClient   Retriever   LlmGateway / Embedder
                    (REST, ETag)   (vec+FTS+RRF) (ChatModel / ONNX)
                          \           |           /
                       PostgreSQL 17 + pgvector
```

| Package | Responsibility |
|---|---|
| `api` | REST controllers, DTOs, RFC 7807 errors |
| `mcp` | MCP tool definitions and wiring |
| `ingest` | GitHub client, loaders, markdown chunker, job runner |
| `embed` | Embedder abstraction, ONNX implementation, batching |
| `retrieve` | Vector search, keyword search, RRF, retriever |
| `answer` | Prompt builder, LLM gateway, citation verifier, ask service |
| `ratelimit` | Token bucket, rate-limit filter |
| `cache` | Caffeine caches and keying |
| `security` | API-key filter, SSRF guard |
| `observability` | Metrics, MDC/request-id filter |
| `config` | Typed configuration properties |

## Data flow: `/ask` (planned)

1. Authenticate API key; rate-limit by key (fallback IP).
2. Normalize question; check answer cache by `(repoId, contentVersion, question, topK)`.
3. Embed the question (cached by normalized text).
4. Run vector and keyword retrieval in parallel (top 30 each), fuse with RRF (k = 60).
5. Build prompt with numbered, delimited context blocks; call the LLM with structured output.
6. Verify citations; refuse if evidence is insufficient.
7. Record metrics and query log; return answer, citations, `refused`, `latencyMs`, `cached`.

## Ingestion pipeline (implemented)

1. `SsrfGuard` validates the URL and resolves `github.com` to a public address.
2. `RestGitHubClient` fetches repo metadata, the recursive tree, README/top-level/docs markdown
   (ETag-aware), and up to 300 issues (pull requests filtered out).
3. `MarkdownChunker` splits on headings, keeps `heading_path`, preserves tables and fenced code
   blocks, and targets 500 tokens with ~12% overlap (hard max 800).
4. Changed documents run on virtual threads; embeddings are batched and bounded by a semaphore
   (ONNX is CPU-bound).
5. Each document is written in its own transaction (upsert document, replace chunks). Removed
   documents are deleted (chunks cascade). `content_version` is a hash of all document hashes.
6. Job and repository status are updated; failures mark both `FAILED` with the error message.

## Database

Flyway `V1__initial_schema.sql` creates `repository`, `document`, `chunk`, `ingestion_job`,
`query_log`, `api_key`, the `vector` extension, a generated `tsvector` column with a GIN index,
and an HNSW cosine index on `chunk.embedding` (384 dims).

## Concurrency

- Virtual threads for request handling and ingestion (`spring.threads.virtual.enabled=true`).
- CPU-bound embedding is bounded by a `Semaphore` (configurable); outbound calls are bounded and
  time-bounded.
- JDK 25 is the runtime, so JEP 491 (no monitor pinning for `synchronized`) applies. Still,
  blocking I/O never runs inside `synchronized`; `ReentrantLock` is preferred.

## Local stack

`docker compose up` starts Postgres+pgvector (host 55432), Prometheus (9090), Grafana (3001).
The app runs from Maven on host port 8081; Prometheus scrapes
`host.docker.internal:8081/actuator/prometheus`.

## Failure modes (planned)

| Failure | Behavior |
|---|---|
| GitHub rate limit / 5xx | Backoff, retry with ETag, job marked FAILED with error text |
| Embedding model missing | Readiness probe fails; app does not accept traffic |
| LLM timeout | `/ask` returns 504 ProblemDetail within the overall deadline |
| Context insufficient | Answer `refused=true`, no fabricated citations |
| Injected instructions in docs | Treated as data; tests prove no compliance |

## HUMAN CHECKPOINT 1: Design Overview (human-written)

> **TODO (human):** Write this section in your own words: how the pieces fit, why the
> boundaries are where they are, and the request path for `/ask`. The agent must not fill this.

## HUMAN CHECKPOINT 1: Key Tradeoffs (human-written)

> **TODO (human):** Write this in your own words: hybrid vs vector-only, one Postgres vs a
> separate vector store, local embeddings vs API embeddings, single module vs multi-module,
> and what you would change at 10x scale. The agent must not fill this.
