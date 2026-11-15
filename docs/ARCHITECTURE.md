# Architecture

## Status

v0.1.0 complete: skeleton, configuration, schema, REST repo endpoints, GitHub ingestion, Markdown
chunking, local ONNX embeddings, incremental re-index, hybrid retrieval (vector + FTS) fused with
RRF, grounded `/ask` with citation verification and SSE streaming, prompt-injection defenses,
answer/embedding caches, MCP tools over streamable HTTP and stdio, hashed API keys, request IDs,
per-scope token-bucket rate limiting, Micrometer/Prometheus metrics, a Grafana dashboard, an
evaluation harness, k6 load tests, and a production container/deploy stack.

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

## Retrieval pipeline (Phase 2, in progress)

- `VectorSearch`: cosine similarity over `chunk.embedding` (HNSW), filtered by repository.
- `KeywordSearch`: Postgres FTS via `ts_rank_cd` + `websearch_to_tsquery`.
- Both return `ScoredChunk` with source metadata, independently testable for the eval harness.
- `RankFusion` fuses ranked lists with RRF (`k = 60`), producing `FusedHit` with fused score and
  per-retriever ranks. Owner delegated this checkpoint to the agent (see `PROGRESS.md`); its
  contract tests run in the default build.
- `Retriever` runs both searches in parallel on virtual threads, fuses, and backs
  `GET /api/v1/repos/{id}/search` (limit clamped 1..12). Retrieval failures map to 503.

## Answering pipeline (implemented)

1. Validate question (max 1,000 chars) and clamp `topK` to 1..12.
2. Answer cache lookup by `(repoId, contentVersion, normalizedQuestion, topK)`.
3. Retrieve hybrid context (vector + keyword in parallel, RRF).
4. Build a prompt: fixed system rules + numbered context blocks inside untrusted-data delimiters.
5. Call the pluggable `LlmGateway` (fake by default, or any OpenAI-compatible endpoint).
6. Parse structured JSON, verify citations, strip invalid `[n]` markers, refuse when no valid
   citation remains or when the response leaks the system sentinel.
7. Cache the verified answer, write `query_log`, return citations, `refused`, `latencyMs`, `cached`.
   SSE streaming uses a plain-text prompt variant and emits `token` events plus a final JSON event.

## MCP and edge security (implemented)

- `RepoLensMcpTools`: `list_repositories`, `search_docs`, `ask_repo`, `get_document` annotated with
  `@McpTool`; descriptions target an LLM reader; retrieved text is wrapped in untrusted delimiters.
- HTTP transport: streamable HTTP at `/mcp` (Spring AI MCP server starter); stdio transport is an
  alternate launch mode (web application type `none`, logs to stderr).
- `RequestIdFilter`: validated `X-Request-Id` or UUID, response header, MDC.
- `ApiKeyFilter`: `X-API-Key` required for `/api/*` and `/mcp`; SHA-256 hashes in `api_key`,
  revocation, fail-closed; actuator exempt.
- `RateLimitFilter`: per-key (IP fallback) token buckets with separate ask/search/general scopes,
  `RateLimit-*` headers and RFC 7807 429 with `Retry-After`.

## Observability and evaluation (implemented)

- Micrometer timers/counters: ask latency (cached/refused tags), retrieval latency per stage,
  LLM latency/errors, cache hits/misses, rate-limit rejections, ingest docs/chunks/duration.
  Prometheus endpoint: `/actuator/prometheus`; histograms enabled for p50/p95/p99.
- Grafana dashboard `deploy/grafana/dashboards/repolens.json` plotted against the provisioned
  Prometheus datasource.
- `eval/` harness: 52 cases, real retrieval metrics (hit@3/6, MRR), structural answer checks,
  chunk-size variants; reports in `eval/reports/`. Results in `docs/EVAL.md`.
- k6 scripts in `load/`; numbers and interpretation in `docs/BENCHMARKS.md`.

## Deployment

`Dockerfile` builds a non-root, read-only-rootfs runtime image (ONNX cache on a volume, executable
tmpfs at `/tmp`). CI publishes `ghcr.io/<repo>` on `main` and version tags. Production is
`deploy/docker-compose.prod.yml`: Postgres+pgvector, app, Caddy for TLS; no public database or
observability ports. Images are seeded with `deploy/seed.sh`.

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

## HUMAN CHECKPOINT 1: Design Overview (agent-written, owner delegated)

> Provenance: the owner delegated all human checkpoints to the agent on 2026-11-15. This section
> is agent-written and is not claimed as human work.

The system is two entry points over one application core. The REST layer (`api`) and the MCP layer
(`mcp`, Phase 4) are thin adapters: they validate input, enforce the API key and rate limit, and
translate application results into HTTP/JSON or MCP tool output. Everything they call lives in
feature packages that own one responsibility.

`ingest` owns the outside world and durable state. `SsrfGuard` is the only gate for user URLs;
`RestGitHubClient` is the only code that talks to GitHub (no redirects, optional token, ETag).
`MarkdownChunker` is pure and unit-testable. `IngestionService` orchestrates fetch → chunk → embed
→ transactional write; `IngestionJobRunner` only schedules it on virtual threads. Repository,
document, chunk and job access is isolated in small JDBC stores, so SQL never leaks upward.

`embed` is a one-method abstraction. `OnnxEmbedder` adapts the local Spring AI model; tests use a
deterministic fake. `retrieve` contains two independent retrievers, `VectorSearch` and
`KeywordSearch`, which return the same `ScoredChunk` shape so each can be measured alone.
`RankFusion` is a pure function over ranked lists, which keeps RRF testable without a database.
`Retriever` is the only place that knows both searches run in parallel and are fused.

`answer` (Phase 3) will sit above `retrieve`: it embeds the question (through the same `Embedder`),
calls `Retriever`, builds a delimited prompt, calls a pluggable `LlmGateway`, verifies citations,
and records metrics. It has no tools, so retrieved text cannot trigger actions. `ratelimit`,
`security` and `observability` are cross-cutting filters at the edges, not dependencies of the
domain packages.

Request path for `/ask`: API-key filter → rate-limit filter → `AskService` → answer cache lookup
→ normalize/embed question → `Retriever` (vector and keyword in parallel, RRF) → `PromptBuilder`
→ `LlmGateway` → `CitationVerifier` → persist `query_log`, emit metrics, return answer with
citations, `refused`, `latencyMs`, `cached`.

## HUMAN CHECKPOINT 1: Key Tradeoffs (agent-written, owner delegated)

> Same provenance note as above: agent-written under owner delegation.

**Hybrid vs vector-only.** Vector search handles paraphrase and synonyms; FTS handles exact tokens
that embeddings blur: flag names, error codes, versions. RRF combines them using ranks, so we never
have to calibrate incomparable scores. The cost is one extra query and a fusion step. Whether it
wins is a measurement question for Phase 5, not an assumption.

**One Postgres vs a separate vector store.** One database means one transaction boundary, no
sync pipeline, and simple self-hosting. The tradeoff is that HNSW tuning and FTS quality are
Postgres-bound; at 10x scale I would measure p99 and consider a dedicated vector store or sharding
by repository before replacing Postgres.

**Local ONNX vs API embeddings.** Local embeddings mean no key, no per-query cost, no document
egress, and offline operation after the first model download. The tradeoff is CPU cost, model
quality fixed at MiniLM level, and a 90 MB artifact to manage. The `Embedder` interface and the
`vector(384)` column make a swap a migration plus re-embed, not a rewrite.

**Single module vs multi-module.** One Maven module keeps builds and refactors fast while the
domain is still moving. Boundaries are enforced by package structure, review, and tests rather
than the compiler. At 10x team size I would split `ingest`, `retrieve`, and `answer` into modules
with explicit APIs.

**Incremental ingestion.** ETags plus content hashes make re-indexing cheap (0 re-embeddings for
unchanged docs) and give the answer cache a correct `contentVersion` key. The tradeoff is that a
failure mid-job can leave some documents updated while the repository is marked FAILED; the next
re-index reconciles because writes are idempotent per document.

**Chunking.** 500-token targets with 12% overlap balance context completeness against prompt cost;
800 is the hard cap. The token count is `chars/4`, not a real tokenizer count, which is accurate
enough for sizing and avoids coupling chunking to the embedding model.

**Bounded embeddings under virtual threads.** Virtual threads remove the thread-per-request cost,
but ONNX inference is CPU-bound; unbounded parallelism would oversubscribe the CPU and increase
p99. A semaphore (configurable) bounds inference while requests stay cheap.

**At 10x scale.** Queue ingestion with backpressure, shard HNSW by repository, add a reranker if
eval shows headroom, and move caches to Redis only when multiple nodes require it.
