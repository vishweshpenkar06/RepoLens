# Interview questions about RepoPrism (30)

Short model answers. Numbers are from committed reports (`docs/EVAL.md`, `docs/BENCHMARKS.md`) and
include the caveats; do not inflate them.

---

## Architecture and retrieval

### 1. Walk me through the architecture.
REST (`/api/v1`) and MCP (`/mcp`, stdio) are thin adapters over three services: ingestion, search and
ask. Ingestion fetches GitHub docs/issues, chunks them and stores ONNX embeddings in PostgreSQL with
pgvector plus a generated full-text column. Search runs vector and keyword retrieval in parallel and
fuses with Reciprocal Rank Fusion. Ask retrieves context, prompts an LLM, parses structured output,
verifies citations and refuses when evidence is missing. PostgreSQL is the only stateful dependency.

### 2. Why hybrid search instead of vector only?
Vector search handles paraphrase but blurs exact tokens (`httpx.Client`, flags, error codes);
Postgres full-text handles exact terms but misses synonyms. On 56 retrieval-scored eval cases,
vector-only hit@6 was 87.5%, keyword-only 51.8%, hybrid 92.9%. The gain is real but modest (+5.4
points), so I report it as modest rather than a dramatic improvement.

### 3. How does RRF work, and why k = 60?
`score(d) = Σ 1/(k + rank_i(d))`, with 1-based ranks. A document found first by both retrievers scores
2/61 ≈ 0.0328; first by one scores 1/61 ≈ 0.0164. k=60 flattens the curve so agreement across
retrievers matters more than one retriever's confidence. We use ranks because cosine scores and
`ts_rank_cd` scores are not comparable.

### 4. What does the retriever return, and how are ties handled?
`FusedHit` contains the chunk, fused score, 1-based vector rank and keyword rank (null when absent).
`RankFusion` deduplicates within a list at the best rank, sorts by fused score descending, then by
chunk id ascending. That makes output deterministic, which the contract tests pin down.

### 5. Show me the database design.
`repository` -> `document` -> `chunk` with cascading deletes. `chunk` stores `repository_id`
(denormalized for filtering), `heading_path`, content, token count, `vector(384)` embedding and a
generated `tsvector`. Indexes: HNSW cosine on embeddings, GIN on `fts`, B-tree on repository.
`ingestion_job`, `query_log` and `api_key` support operations. Flyway V1 creates it; V2 adds full
document text for `get_document`.

### 6. How do you chunk markdown?
`MarkdownChunker` splits on headings, keeps heading paths, targets 500 tokens with a hard max of 800
and ~12% overlap, keeps tables and fenced code blocks where possible, and strips badge/HTML noise.
Tokens are estimated as characters/4 (ADR-0010) to avoid coupling chunking to the model tokenizer.

### 7. How does re-indexing avoid re-embedding everything?
Files are fetched with `If-None-Match`; a 304 keeps the stored hash. Issues have no useful ETag, so
their title+body SHA-256 is compared. Only changed documents are chunked/embedded. A re-index of a
301-doc repo wrote 0 chunks and took ~0.7 s. Removed documents are deleted by diffing the listing.

### 8. Why local ONNX embeddings?
No paid key, no document egress, works offline after the first ~90 MB model download. MiniLM at 384
dimensions is cheap and good enough for docs. The `Embedder` interface makes it replaceable; changing
models requires a migration because the schema fixes `vector(384)`.

### 9. Why did you use pgvector instead of a separate vector database?
One store means one transaction boundary, no sync pipeline, and simple self-hosting. Postgres also
gives us full-text search for free. The tradeoff is that HNSW/FTS tuning is Postgres-bound; at 10x
scale I would measure a dedicated vector store or shard by repository.

### 10. Why did pgvector choose a sequential scan at 967 chunks, and what did you do?
For a small table, a seq scan was cheaper (2.4 ms) than the HNSW index. Forcing plain HNSW was slower
(16 ms) and returned only 10 of 30 rows because of the repository filter. pgvector 0.8's iterative
HNSW scan returns all 30 rows in 0.85 ms, so vector search uses a dedicated pool with
`hnsw.iterative_scan=relaxed_order` and forced index use.

---

## Grounding, safety, and the LLM

### 11. How do you stop hallucination and force citations?
The prompt restricts answers to supplied context and asks for JSON with `answer`, `citations` and
`refused`. `CitationVerifier` strips markers that do not point to a context block; zero valid
citations becomes a refusal. In the real eval, citation validity was 100%, false refusals 3.6% and
refusal recall 100% on unanswerable cases.

### 12. How do you defend against prompt injection?
Retrieved text is wrapped in explicit untrusted-data delimiters and labelled as data, not
instructions; the answer step has no tools; the system prompt forbids following embedded
instructions and leaking a sentinel. The verifier refuses if the sentinel appears. Four real-model
injection cases were all blocked. This is defense in depth, not a mathematical guarantee.

### 13. What happens when the model refuses with an empty answer?
A real bug: an empty `answer` caused the parser to fall back to the raw JSON string, so the API
returned JSON as text. Fixed so a parsed empty answer stays empty, and the verifier returns the
standard "I don't know based on the provided context." sentence. Covered by parser and service tests.

### 14. What does the LLM judge measure?
A separate model receives the question, numbered context and answer, and returns
`{grounded, score 1-5, reason}` using a committed prompt. The last real run judged 20 non-refused
answers: 90% grounded, average 4.70/5. It is a sample, not all 56 answers, and it uses a different
model from the answerer.

### 15. Why an OpenAI-compatible gateway instead of Anthropic/Spring AI chat?
The owner's key was not Anthropic, and one OpenAI-compatible protocol covers OpenAI, OpenRouter,
Groq, vLLM, LM Studio and Ollama. It also keeps tests on a deterministic fake with no network. The
tradeoff is no provider-native structured-output schema; we ask for JSON, parse tolerantly, and
validate citations ourselves. ADR-0017 records it.

### 16. How does streaming `/ask` work?
SSE emits `token` events from the gateway plus a final JSON event with the verified answer and
citations. Streaming uses a plain-text prompt variant with `[n]` markers, because streaming JSON
would show partial braces. The final event is authoritative; if verification refuses, the client
should discard streamed tokens.

---

## Concurrency and performance

### 17. What do virtual threads buy you here, and what is pinning?
Virtual threads make blocking waits cheap by unmounting from carrier threads. That helps requests
waiting on LLM/GitHub I/O. Pinning was when a virtual thread inside `synchronized` could not unmount;
JEP 491 fixed that in JDK 24, and RepoPrism runs JDK 25. For DB-bound `/search`, virtual threads did
not help: 2,281.5 vs 2,272.7 req/s for platform threads after the fixes.

### 18. Why bound embedding concurrency if virtual threads are cheap?
ONNX inference is CPU-bound. Thousands of virtual threads would just oversubscribe the CPU and raise
latency. A configurable `Semaphore` limits concurrent inference batches while the document pipeline
stays on virtual threads. Virtual threads remove the cost of waiting, not the cost of computing.

### 19. Why was search p95 664–804 ms at 200 users, and what fixed it?
Profiling found 255 requests queued on a 10-connection Hikari pool, four DB calls per request, and
Postgres using ~18 cores on sequential vector scans. I cached API-key authentication and repository
existence, made the pool configurable (20), and moved vector search to a dedicated iterative-HNSW
pool. Median throughput went from 127.7 to 2,281.5 req/s and p95 from 1,527 ms to 4 ms on the same
967-chunk dataset. Embedding cache was not the issue (7,221 hits took 0.06 s).

### 20. How do you know the benchmark is honest?
Same repo, query set, k6 script and machine; three runs per configuration; medians and ranges
reported; virtual and platform variants; machine specs and commands in `docs/BENCHMARKS.md`. I also
document that the host is shared, discard runs under heavy unrelated load, and note that 2,281 rps is
limited by k6's 50 ms think time. Rate limiting is disabled to measure capacity.

### 21. How would you scale this to 1,000 repositories and multiple nodes?
Shard HNSW by repository or move vectors to a dedicated store; queue ingestion with backpressure;
move caches and rate limiting to Redis; make the app stateless behind a load balancer; set per-repo
ingestion quotas. Before all that, measure where the next bottleneck is rather than guessing.

### 22. What would you change if retrieval quality dropped after a re-index?
Check `content_version`, document/chunk counts, and whether embedding dimensions/model changed. Use
the eval harness to compare hit@k/MRR before and after; diff chunking, ETag handling and deleted
documents. The query log and content version make stale-cache issues diagnosable.

---

## Security and operations

### 23. How are API keys stored and checked?
Only SHA-256 hashes are stored in `api_key`. Lookup is by hash, then `MessageDigest.isEqual` compares
the hash constant-time. Keys can be revoked; successful auth is cached for 60 s for performance, so
revocation can take up to that TTL (`REPOPRISM_API_KEY_CACHE_TTL=0s` disables caching). `/api/*` and
`/mcp` require a key; actuator health/metrics are exempt.

### 24. How do you prevent SSRF?
Only `https://github.com/{owner}/{name}` is accepted: no credentials, ports, query or fragments; the
host must resolve to public addresses; the HTTP client never follows redirects. The guard runs before
any outbound call and is tested with private/loopback/link-local resolutions.

### 25. How does rate limiting work?
A token bucket per API key (IP fallback) with separate ASK/SEARCH/GENERAL capacities and refill rates.
It uses a `ReentrantLock` and an injectable nanosecond clock so refill/concurrency tests are
deterministic. Rejections return 429 with `Retry-After` and `RateLimit-*` headers; accepted responses
also carry the headers. It is per node; Redis would be needed for a cluster.

### 26. How is the container hardened?
Multi-stage build, JRE 25 runtime, non-root uid 1001, read-only root filesystem,
`no-new-privileges`, healthcheck, dependency layers cached separately. ONNX extracts a native library
to `/tmp`, so the container mounts an executable 512 MB tmpfs; without `exec` startup fails. Dev
Compose binds ports to `127.0.0.1`; production exposes only Caddy 80/443.

### 27. What is in your CI?
`./mvnw verify` on JDK 25 with Testcontainers, Spotless and JaCoCo; gitleaks; Docker build with a
non-root smoke check; GHCR publish on `main`/`master` and version tags; Dependabot for Maven,
Actions and Docker. Coverage gates are 70% overall with stricter targets for chunker/retrieval/
rate-limiting/verifier.

### 28. How do request IDs and logs work?
`RequestIdFilter` accepts a safe `X-Request-Id` or generates a UUID, puts it in MDC, echoes it in the
response, and emits one access line per non-actuator request. Structured ECS logs include service name
and version; keys and prompts are never logged at INFO.

---

## Evaluation and reflection

### 29. How did you evaluate quality, and what are the limits?
72 cases across three real repos plus an injection fixture: hit@k and MRR for vector/keyword/hybrid,
citation validity, refusal precision/recall, injection blocking, and an LLM judge on a 20-case sample.
Limits: cases are agent-drafted and need review; answers used `gpt-oss-20b` with top-k 5 because of
free-tier quotas; the judge is a different model; this is not a frontier-model benchmark.

### 30. What is the biggest weakness of the project today?
Real answer quality is measured on a small free-tier model and a modest case set, not a paid frontier
model or real user traffic. Retrieval gains are modest. Caches/rate limits are single-node. There is no
public demo URL yet, and the human checkpoints were delegated to the agent, so I should not claim them
as personally written. The strengths are the measured pipeline, citation/refusal behavior, and the
hot-path performance work.
