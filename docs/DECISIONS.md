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

**Why:** the plan requires verified, consistent versions. This is one coherent set.
**Alternatives:** Boot 4.0.x (older), Spring AI 2.1.0-RC2 (not GA), JDK 21 (no distro package on
Nobara 44; JDK 25 is the newest LTS and is supported).
**Tradeoff:** Boot 4.1 uses renamed starters (`spring-boot-starter-webmvc`, modular `*-test`
starters); examples written for Boot 3 may not apply.

## ADR-0002: Spring AI over LangChain4j

**Decision:** Spring AI 2.0.2.
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
