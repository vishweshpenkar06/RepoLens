# Architecture Decision Records

Short ADRs for non-trivial choices, oldest first. Versions verified against Maven Central and
official Spring documentation on 2026-11-15.

## ADR-0001: Version set (verified, not from memory)

**Decision**

| Component | Version | Source of truth |
|---|---|---|
| Java (compile target) | 25 (LTS) | `java-25-openjdk-devel`; Spring Boot 4.1.2 supports Java 17–27 |
| Spring Boot | 4.1.2 | `maven-metadata.xml` for `spring-boot-starter-parent`; docs.spring.io system requirements |
| Spring AI | 2.0.2 | Spring AI docs: 2.0.x supports Boot 4.0.x and 4.1.x |
| MCP Java SDK | 2.1.0 | `spring-ai-mcp:2.0.2` declares `io.modelcontextprotocol.sdk:mcp:2.1.0`; do not override |
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.7-pg17` | Docker Hub tag list (Postgres 17, ext 0.8.7) |
| Testcontainers | Boot-managed 2.x | Boot BOM; `testcontainers-postgresql` artifact |
| Prometheus | v3.16.0 | Docker Hub tag list |
| Grafana | 13.3.1 | Docker Hub tag list |
| Spotless | 3.11.0 | Maven Central `<release>` |
| google-java-format | 2.0.0 | 1.37.0 failed on JDK 25 (`InvocationTargetException`); 2.0.0 works with Spotless 3.11.0 |
| JaCoCo | 0.8.16 | Maven Central `<release>` |
| Jackson | 3.1.7 (`tools.jackson`) | Boot 4 default; Jackson 2 is not on the classpath |

**Why:** the plan requires verified, consistent versions. This is one coherent set.
**Alternatives:** Boot 4.0.x (older), Spring AI 2.1.0-RC2 (not GA), JDK 21 (no distro package on
Nobara 44; JDK 25 is the newest LTS and is supported).
**Tradeoff:** Boot 4.1 uses renamed starters (`spring-boot-starter-webmvc`, modular `*-test`
starters); examples written for Boot 3 may not apply.

## ADR-0002: Spring AI over LangChain4j

**Decision:** Spring AI 2.0.2 for embeddings (ONNX Transformers). Chat generation uses a thin
OpenAI-compatible gateway instead of Spring AI chat starters; see ADR-0017.
**Why:** first-party Spring Boot integration (chat models, structured output, MCP server starters,
ONNX Transformers embedding model), and 2.0.x officially targets Boot 4.1.x.
**Alternatives:** LangChain4j (mature, but more manual wiring and a second AI abstraction).
**Tradeoff:** Spring AI is younger; API churn between 1.x and 2.x is real.

## ADR-0003: Java 25 target with virtual threads

**Decision:** compile and run on JDK 25, `spring.threads.virtual.enabled=true`.
**Why:** LTS, supported by Boot 4.1.2, and JEP 491 (JDK 24) removed `synchronized` monitor
pinning, which the plan calls out explicitly. Detail in `docs/ARCHITECTURE.md`.
**Alternatives:** JDK 21 (not packaged for this distro; same virtual-thread model but pinning
issue remains pre-24).
**Tradeoff:** some tooling lags behind JDK 25.

## ADR-0004: PostgreSQL + pgvector for hybrid retrieval

**Decision:** one Postgres instance with `vector` extension, HNSW cosine index, and generated
`tsvector` + GIN index; no separate search engine.
**Why:** satisfies vector + keyword search in a single transactional store; removes an extra
service; simple ops for a self-hosted project.
**Alternatives:** OpenSearch/Elasticsearch (two stores, sync complexity), Qdrant + Postgres
(duplicate data), pg_trgm only (weak semantics).
**Tradeoff:** Postgres FTS is weaker than Lucene-style analyzers; RRF compensates.

## ADR-0005: Single Maven module, package-by-feature

**Decision:** packages `api`, `mcp`, `ingest`, `embed`, `retrieve`, `answer`, `ratelimit`,
`cache`, `security`, `observability`, `config`, `common`.
**Why:** small team, fast iteration; feature packages keep boundaries visible without
multi-module build overhead.
**Alternatives:** multi-module Maven (premature), layer-first packages (weak boundaries).
**Tradeoff:** enforce boundaries by review, not the compiler.

## ADR-0006: Host port remapping

**Decision:** Postgres `55432`, app `8081`, Grafana `3001`; Prometheus stays `9090`.
**Why:** ports 5432, 8080 and 3000 are already bound by unrelated containers on this machine
(`docs/ENVIRONMENT.md`).
**Alternatives:** stop the other stack (not acceptable), use container-only networking (breaks
`curl localhost`).
**Tradeoff:** non-default ports must be documented in README and `.env.example`.

## ADR-0007: Typed record configuration

**Decision:** `@ConfigurationProperties` records for every non-trivial group, bound with
`@ConfigurationPropertiesScan`; all values overridable by env vars.
**Why:** no scattered `@Value`, fail-fast binding, testable via `ApplicationContextRunner`.
**Tradeoff:** nested record defaults require `@DefaultValue` on every component.

## ADR-0008: Local ONNX embeddings as default

**Decision:** default embedder is the Spring AI ONNX Transformers model
(`all-MiniLM-L6-v2`, 384 dims); embedding dimension is fixed at 384 in the schema.
**Why:** the project must run with no paid API key; 384 dims is cheap and good enough for docs.
**Alternatives:** OpenAI embeddings (key + cost), Ollama embeddings (extra daemon).
**Tradeoff:** changing models requires a migration and full re-embed (noted in V1 SQL comment).

## ADR-0009: Flyway owns the schema, JdbcClient owns the queries

**Decision:** Flyway migrations under `src/main/resources/db/migration`; Spring JDBC
`JdbcClient` for vector/FTS queries. No JPA.
**Why:** pgvector operators and `ts_rank_cd` are plain SQL; JPA adds mapping noise for no gain.
**Tradeoff:** manual row mapping; mitigated by small record mappers.

## ADR-0010: Approximate token counting (characters / 4)

**Decision:** chunk sizes and stored `token_count` use `ceil(chars / 4)`.
**Why:** avoids a tokenizer dependency in the chunker, is deterministic, and is close enough to
MiniLM's wordpiece counts for 500/800-token targets. Real tokenization still happens inside the
ONNX model at embed time.
**Alternatives:** JTokkit (extra dependency, slower), the ONNX tokenizer (couples chunking to the
model and is hard to use before the model is loaded).
**Tradeoff:** `token_count` is an estimate, not an exact tokenizer count. Documented on
`MarkdownChunker`.

## ADR-0011: Incremental ingestion via ETag + content hash

**Decision:** files are re-fetched with `If-None-Match`; a `304` keeps the stored hash. Issues have
no useful ETag, so they are always listed but re-embedded only when the SHA-256 of title+body
changes. `repository.content_version` is a SHA-256 over all `(kind|path=hash)` pairs and changes
only when some document changes.
**Why:** re-indexing a 300-issue repo went from ~8 s to ~0.7 s with zero re-embeddings, and the
content version gives Phase 3 a correct cache key.
**Alternatives:** always re-embed (wasteful), mtime-only (GitHub does not expose stable mtimes).
**Tradeoff:** removed documents are detected by diffing the current listing against stored rows and
deleted (chunks cascade).

## ADR-0012: Local ONNX embeddings with startup warmup

**Decision:** `spring-ai-starter-model-transformers` with `all-MiniLM-L6-v2` (384 dims), model and
tokenizer downloaded from Hugging Face on first use and cached under
`${EMBEDDING_CACHE_DIR:-$TMPDIR/repolens-onnx}`. `OnnxEmbedder` warms up at startup (configurable)
and reports `UP` only after a successful embedding.
**Why:** no paid key, offline after the first download, readiness reflects real model health.
**Tradeoff:** first startup downloads ~90 MB; tests disable the model
(`spring.ai.model.embedding=none`) and inject a deterministic fake.

## ADR-0013: SSRF guard before any outbound call

**Decision:** user URLs must be `https://github.com/{owner}/{name}` with no credentials, port,
query or fragment. The host is resolved and every resolved address must be public (loopback,
private, link-local, site-local, CGNAT, multicast and IPv6 `fc00::/7` rejected). The GitHub HTTP
client never follows redirects.
**Why:** prevents SSRF and open-proxy behavior; validation is independent of the HTTP client.
**Alternatives:** deny-list only (bypassable), resolving after redirect (too late).
**Tradeoff:** validation depends on DNS and fails closed when resolution fails.

## ADR-0014: Issue ingestion limits and README detection

**Decision:** ingest README (including extensionless `README`), top-level `*.md`, `docs/**/*.md`,
and up to 300 issues/discussions-by-issue-endpoint (configurable, `GITHUB_ISSUES_ENABLED`).
**Why:** keeps the first index bounded and defensible; `octocat/Hello-World` has an extensionless
README and >300 issues, which motivated both rules.
**Tradeoff:** docs outside `docs/` at depth > 1 are skipped; raise the limit later if needed.

## ADR-0015: Hybrid retrieval shape (vector + FTS, union then RRF)

**Decision:** `VectorSearch` uses `1 - (embedding <=> query)` over an HNSW cosine index, filtered
by `repository_id`, ordered by cosine distance, limited (default 30). `KeywordSearch` uses
`ts_rank_cd(fts, websearch_to_tsquery('english', q))` with a GIN index, ordered by rank and chunk id,
limited. Both return `ScoredChunk` (chunk + document metadata + retriever score). `RankFusion`
returns `FusedHit` with fused score and both 1-based ranks (null when absent).
**Why:** keeps each retriever independently testable and measurable in the eval harness (vector
only, keyword only, hybrid), which the plan requires. `websearch_to_tsquery` is forgiving of user
input and supports quotes/negation.
**Tradeoff:** scores from the two retrievers are not comparable, which is why fusion is rank-based.

## ADR-0016: Human checkpoints were delegated to the agent

**Decision:** the project owner delegated all six HUMAN CHECKPOINTS to the agent on 2026-11-15.
Checkpoint code and prose (RRF, checkpoint-1 design write-up, and later the token bucket, eval
cases, benchmark interpretation, README results) are agent-written. Checkpoint tests run in the
default build. `PROGRESS.md` records this provenance.
**Why:** explicit owner instruction; the plan's default (stop and wait) would otherwise block the
build on human-owned code.
**Tradeoff / integrity note:** the MASTERPLAN appendix says to be honest on the resume. Do not
claim these as personally written; the honest framing is "implemented with AI assistance, reviewed
and understood by me" only after the owner actually reviews and can explain them. Provenance is
also recorded in the affected Javadoc.


## ADR-0017: OpenAI-compatible LLM gateway instead of an Anthropic-only default

**Decision:** `LlmGateway` has two implementations: `FakeLlmGateway` (default, no key, deterministic)
and `OpenAiCompatibleLlmGateway` (any `/chat/completions` endpoint). Provider is selected with
`LLM_PROVIDER`; OpenAI, OpenRouter, Groq, Together, vLLM, LM Studio and Ollama (`/v1`) all work with
the same code by changing base URL, key and model. Anthropic's native API is intentionally not
wired.
**Why:** the owner's key will not be Anthropic and the exact provider is unknown. One OpenAI-compatible
protocol covers the practical options without new code; the fake keeps tests and load tests free of
keys and network variance. The plan allows alternative AI libraries with an ADR.
**Alternatives:** Spring AI chat starters per provider (multiple starter beans, selector
configuration, more coupling), native Anthropic client (excluded by owner), LangChain4j (second AI
stack).
**Tradeoff:** no provider-native structured-output schema enforcement; we ask for JSON, parse
tolerantly, and validate citations ourselves. Streaming uses plain SSE deltas, so the streaming
prompt asks for text with `[n]` markers rather than JSON.

## ADR-0018: Prompt-injection and citation defenses

**Decision:** retrieved text is wrapped in explicit `<<<BEGIN/END_UNTRUSTED_CONTEXT>>>` delimiters,
the system prompt labels it as data, forbids following its instructions, forbids tool use, and
includes a sentinel. Answers must cite context blocks with `[n]`; `CitationVerifier` strips
out-of-range markers, refuses when no valid citation remains, and refuses if the answer contains the
system sentinel. The answer step has no tools and the service never executes retrieved instructions.
**Why:** defense in depth that is testable without a real model: prompt construction, no-tool
architecture, citation validation, and leak detection.
**Tradeoff:** this cannot prove a model will never be tricked. Phase 5 eval includes injection cases
against a real provider; until then the fake-provider tests demonstrate the service-side guards.

## ADR-0019: Answer and query-embedding caches

**Decision:** Caffeine answer cache keyed by `(repositoryId, contentVersion, normalizedQuestion,
topK)` with a 1 h TTL and 10k max entries; query embeddings cached by normalized text with a 24 h
TTL. The content version changes on re-ingestion, which invalidates answers without explicit
eviction.
**Why:** repeated questions are cheap and re-indexing cannot serve stale answers; embedding cache
avoids re-running ONNX for identical queries.
**Tradeoff:** in-process only (per node); Redis is the documented stretch goal for multi-node.

## ADR-0020: MCP server on Spring AI 2.0.2 with dual transports

**Decision:** `spring-ai-starter-mcp-server-webmvc` with protocol `streamable` and
`@McpTool` annotated methods for HTTP (`/mcp`), plus the same annotation-scanned tools over stdio
(`spring.ai.mcp.server.stdio=true`, web application type `none`, `logback-stdio.xml` to keep logs off
stdout). Tool results wrap retrieved text in untrusted-document delimiters.
**Why:** the starter handles JSON-schema generation, sync tool registration and streamable HTTP;
one tools class serves both transports. Stdio gives Claude Desktop a no-network, no-header option;
HTTP gives remote clients a standard endpoint.
**Alternatives:** manual MCP SDK wiring (more code), SSE transport (deprecated in 2.0), separate
codebases per transport.
**Tradeoff:** stdio and HTTP cannot share one process cleanly (transport provider conflict), so
stdio is a documented alternate launch mode; HTTP MCP needs the API key header, which some clients
do not support (use stdio there).

## ADR-0021: API keys as SHA-256 hashes, fail closed

**Decision:** `X-API-Key` is required for `/api/*` and `/mcp` by default. Only the SHA-256 hash is
stored in `api_key`; validation looks up the unique hash and rejects revoked rows. A configured
`REPOLENS_API_KEY` is bootstrapped at startup (hash only). Missing or invalid keys return an RFC
7807 401. Actuator endpoints are exempt.
**Why:** no plaintext secrets at rest, simple revocation (`revoked=true`), and secure-by-default
deployment. Hash lookup cannot leak the secret; SHA-256 preimage resistance makes timing attacks on
comparison impractical.
**Alternatives:** bcrypt/Argon2 (slow for per-request auth), plaintext keys (unacceptable),
JWT/OAuth (out of scope).
**Tradeoff:** rotating keys requires inserting a new hash and revoking the old row; there is no
admin API yet (SQL or restart with a new env value).

## ADR-0022: Token-bucket rate limiting per key and scope

**Decision:** `TokenBucket` (ReentrantLock, injectable `LongSupplier` nanosecond clock) with
separate buckets for `ASK`, `SEARCH` and `GENERAL`, keyed by API-key id falling back to client IP.
Defaults: ask 10 capacity / 0.5 tokens/s, search 30 / 5, general 60 / 10. Rejections are 429 with
`Retry-After` and `RateLimit-Limit`/`Remaining`/`Reset`/`Scope` headers; allowed responses also get
the `RateLimit-*` headers.
**Why:** matches the plan, protects the expensive LLM path separately from search, and the injectable
clock makes refill behaviour and concurrency testable deterministically.
**Alternatives:** fixed windows (bursty boundaries), Guava RateLimiter (permit-based, less control),
Redis (out of scope until multi-node).
**Tradeoff:** in-process buckets are per node; the stretch goal is a Redis-backed shared limiter.
Provenance: HUMAN CHECKPOINT 3 was delegated by the owner to the agent (ADR-0016).

## ADR-0023: Store document text for `get_document`

**Decision:** migration `V2` adds `document.content`; ingestion writes the full (already fetched)
text, and `get_document` returns it capped at 20,000 characters inside untrusted delimiters.
**Why:** the MCP tool contract requires full document text; reconstructing it from overlapping
chunks would duplicate text and lose fidelity. Storage is bounded by the existing 1 MiB per-file
ingest cap.
**Tradeoff:** duplicate storage (document text plus chunks). Acceptable at this scale; a future
optimization can drop the column and reconstruct from chunks if storage matters.

## ADR-0024: Micrometer naming and Prometheus histograms

**Decision:** metric names use Micrometer dot notation (`repolens.ask.latency`, `repolens.retrieval.latency`,
`repolens.cache.hits`, `repolens.ratelimit.rejected`, `repolens.ingest.*`); Prometheus exposes them
with `_seconds`/`_total` suffixes. Histograms are enabled for ask, retrieval, LLM and ingest timers via
`management.metrics.distribution.percentiles-histogram`.
**Why:** histogram buckets are required for `histogram_quantile` in Grafana; summaries only export
count/sum/max and cannot produce p95/p99.
**Tradeoff:** more Prometheus series (per tag set); acceptable at this scale.

## ADR-0025: Evaluation methodology and fake-LLM limitation

**Decision:** 52 cases over two real repos plus a synthetic injection fixture; retrieval metrics are
real (ONNX embeddings, pgvector, Postgres FTS), while answer metrics are structural using the fake
LLM. Chunk-size variants rebuild chunks from stored document text. Reports are committed under
`eval/reports/`.
**Why:** retrieval quality can be measured without a paid key; the plan requires hit@k/MRR and a
mode comparison. The fake model cannot measure real groundedness, so it is clearly labelled.
**Tradeoff:** citation validity/refusal/injection numbers are plumbing checks, not model quality,
until a real provider is configured. Provenance: CHECKPOINT 4 (case curation) was delegated to the
agent.

## ADR-0026: Load-test methodology and the virtual-thread result

**Decision:** k6 scripts for search (200 VUs), stubbed ask (fake LLM 300 ms), and a small real-ask
sanity run. Benchmarks compare virtual vs platform threads, cache on/off, retrieval stages and
ingestion concurrency. Numbers and interpretation live in `docs/BENCHMARKS.md`.
**Why:** the plan requires measured numbers, including the honest virtual-thread finding.
**Finding:** for DB-bound search, virtual threads gave no throughput gain (273.5 vs 278.5 req/s,
within noise); caching was the dominant lever for `/ask` (808 vs 53 req/s), and embedding concurrency
2→8 improved ingestion only ~7% because GitHub fetch dominates.
**Tradeoff:** single runs on a shared desktop; treat ±10% as noise. Provenance: CHECKPOINT 5 was
delegated to the agent.
