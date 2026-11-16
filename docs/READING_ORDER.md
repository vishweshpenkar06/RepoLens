# Reading order (15 core files)

Read in this order. For each file, keep one question in mind; if you can answer it, move on. Do not
try to understand every Spring annotation on the first pass.

### 1. `docs/ARCHITECTURE.md` + `README.md`
**Question:** what are the components and which one owns which responsibility?
Look at the Mermaid diagram, the package table and the request flows. Everything after this is detail.

### 2. `pom.xml`
**Question:** what is on the classpath and why?
Find Spring Boot, Spring AI (embeddings), the MCP server starter, Caffeine, Testcontainers,
WireMock, Spotless and JaCoCo. Note the `eval` profile.

### 3. `src/main/resources/application.yml`
**Question:** how does configuration reach the app?
Look for `${ENV:default}` placeholders, virtual threads, Hikari, MCP, LLM, security and rate limits.

### 4. `src/main/resources/db/migration/V1__initial_schema.sql`
**Question:** what does durable state look like?
Tables: repository, document, chunk, ingestion_job, query_log, api_key. Note `vector(384)`, the
generated `fts` tsvector, GIN and HNSW indexes. Read V2 only to see why `document.content` exists.

### 5. `src/main/java/com/vishwesh/repolens/RepolensApplication.java`
**Question:** how does Spring find everything?
`@SpringBootApplication` + `@ConfigurationPropertiesScan`. This is the entry point.

### 6. `config/RepoLensProperties.java`
**Question:** where is configuration typed?
Nested records for GitHub, embedding, retrieval, ask, LLM and caches. Defaults are part of the
contract; tests instantiate defaults.

### 7. `api/RepoController.java`
**Question:** how thin is the HTTP layer?
It validates, calls a service, maps results. `AskController` and `SearchController` follow the same
pattern; `api/ApiExceptionHandler.java` maps exceptions to RFC 7807 problem details.

### 8. `api/RepoService.java`
**Question:** what happens between "register a repo" and "job starts"?
It creates repository + job rows and hands off to `IngestionJobRunner`. Note the small transaction
scope; the actual ingestion runs asynchronously.

### 9. `ingest/IngestionService.java`
**Question:** how does one document become searchable chunks?
Fetch -> chunk -> embed in batches with a `Semaphore` -> transactional write per document -> delete
removed documents -> `content_version` -> READY/FAILED. This is the busiest class; read it slowly.

### 10. `ingest/MarkdownChunker.java`
**Question:** what exactly is a chunk?
Heading paths, fenced code blocks, tables, size target/max and overlap. The split helpers are pure
functions, easy to test and change.

### 11. `ingest/SsrfGuard.java` + `ingest/RestGitHubClient.java`
**Question:** how do we safely talk to GitHub?
The guard validates scheme/host/path and public DNS; the client handles ETags, retries, rate limits
and never follows redirects. These two are the only outbound path for user-provided URLs.

### 12. `embed/OnnxEmbedder.java`
**Question:** how does text become 384 numbers?
It adapts Spring AI's ONNX model, warms up at startup, caches query vectors, and feeds readiness.
The `Embedder` interface keeps the rest of the code model-agnostic.

### 13. `retrieve/VectorSearch.java` + `retrieve/KeywordSearch.java`
**Question:** what SQL finds candidates, and why is there a separate vector pool?
Both return `ScoredChunk`s. The vector pool sets iterative-HNSW session options; keyword search uses
the main pool and GIN. This is where the benchmark fix lives.

### 14. `retrieve/RankFusion.java` + `retrieve/Retriever.java`
**Question:** how are two ranked lists combined without comparing their scores?
RRF only uses ranks. `Retriever` runs both searches in parallel, then calls `fuse`. Read the tests
next to these files; they pin down ties, duplicates and missing results.

### 15. `answer/AskService.java` + `answer/PromptBuilder.java` + `answer/CitationVerifier.java`
**Question:** what stops the model from inventing an answer?
Cache -> retrieve -> prompt with untrusted delimiters -> LLM JSON -> parse -> verify citations ->
refuse on zero valid citations or system leak -> cache/log. This is the core answer pipeline.

---

## Optional second pass (after the core)

- `ratelimit/TokenBucket.java` and `RateLimitFilter.java` — concurrency and 429 behaviour.
- `security/ApiKeyService.java`, `ApiKeyFilter.java`, `RequestIdFilter.java` — edge security.
- `mcp/RepoLensMcpTools.java` — how the same features are exposed to AI assistants.
- `observability/RepoLensMetrics.java` and `deploy/grafana/dashboards/repolens.json` — what is measured.
- `src/test/java/.../eval/EvalRunnerTests.java` + `eval/judge-prompt.md` — how quality is measured.
- `load/*.js` and `docs/BENCHMARKS.md` — how performance claims were produced.

## Suggested pace

- Day 1: files 1–6 (orientation + configuration + schema).
- Day 2: files 7–11 (API + ingestion).
- Day 3: files 12–15 (embeddings + retrieval + answers).
- Day 4: optional pass + run `./mvnw verify`, then `./mvnw -Peval verify`.
