# Progress

## Phase 0 — Foundations (complete)

- Environment verified; JDK 25 installed, Docker group fixed (`docs/ENVIRONMENT.md`).
- Maven wrapper (Maven 3.10.0), Spring Boot 4.1.2 skeleton, package layout.
- Spotless + JaCoCo wired into `verify`; typed configuration records.
- Flyway `V1` schema (pgvector, FTS, HNSW) with Testcontainers migration tests.
- `docker-compose.yml`: Postgres+pgvector, Prometheus, Grafana; host ports remapped.
- GitHub Actions CI: build + tests + Testcontainers + coverage artifact.
- Docs skeleton: README, ARCHITECTURE, DECISIONS, BACKLOG, ENVIRONMENT.

## Phase 1 — Ingestion (complete)

- `RestGitHubClient`: tree/contents/issues, ETag 304, rate-limit backoff, optional token, no redirects.
- `SsrfGuard`: github.com only, public-IP resolution, rejects credentials/ports/query/fragment.
- `MarkdownChunker`: heading paths, code fences, tables, badge/HTML cleanup, 500/800/12% rules.
- `OnnxEmbedder`: local all-MiniLM-L6-v2 (384 dims), startup warmup, readiness indicator.
- `IngestionService` + virtual-thread job runner: batched embeddings with semaphore, transactional
  per-document writes, incremental re-ingest, deletion of removed docs, content version.
- REST: `POST /api/v1/repos`, `GET /api/v1/repos`, `GET /api/v1/repos/{id}`,
  `POST /api/v1/repos/{id}/reindex`; RFC 7807 errors.
- 83 tests; chunker line coverage 98.5%. Real run: `octocat/Hello-World` (301 docs: README + 300
  issues, 319 chunks, 384 dims); re-index wrote 0 chunks in ~0.7 s.

## Phase 2 — Retrieval (complete)

- `VectorSearch` (pgvector cosine) and `KeywordSearch` (FTS `ts_rank_cd`) with repo filtering,
  limits and source metadata.
- `RankFusion`: RRF with `k = 60`, deterministic tie-break on chunk id, input immutability.
  11 contract tests run in the default build.
- `Retriever`: parallel vector + keyword search on virtual threads, 15 s stage timeout, limit
  clamp 1..12.
- `GET /api/v1/repos/{id}/search?q=&k=` with RFC 7807 errors (400 invalid query, 404 repo, 503
  retrieval failure); snippet + both ranks + fused score.
- 113 tests green, coverage gates met. Real check on `octocat/Hello-World`: `hello` returns the
  README (vector #2, keyword #4) plus issue hits; `fork` returns vector-only hits, proving both
  branches and fusion are live.

**Checkpoint provenance:** the owner delegated HUMAN CHECKPOINT 1 (design write-up) and
HUMAN CHECKPOINT 2 (RRF) to the agent on 2026-11-15. Both are agent-written; see ADR-0016. Do not
present them as human-written unless the owner reviews and can explain them.

## Phase 3 — Answering (complete)

- `PromptBuilder`: system rules, numbered untrusted context delimiters, JSON output contract,
  separate streaming prompt; sentinel for leak detection.
- `LlmGateway`: fake (default, deterministic, configurable latency) + OpenAI-compatible HTTP gateway
  (OpenAI/OpenRouter/Groq/vLLM/Ollama), JSON mode, SSE streaming. Owner's key is not Anthropic;
  see ADR-0017.
- `AnswerParser` + `CitationVerifier`: tolerant JSON parsing, marker/field validation, invalid
  markers stripped, refusal with zero valid citations, system-prompt leak refusal.
- `AskService`: cache lookup, retrieval, generation, verification, `query_log`, `cached` flag;
  `POST /ask` and `GET /ask/stream` (SSE `token` + `final` events).
- Caffeine answer cache keyed by `contentVersion` (invalidates on re-ingest) and query-embedding
  cache; 41 new tests (total 154). Live check on `octocat/Hello-World`: grounded fake answer cites
  the issue source; repeated normalized question returns `cached=true`; SSE streams tokens.

## Phase 4 — MCP, security, rate limiting (complete)

- MCP tools (`list_repositories`, `search_docs`, `ask_repo`, `get_document`) via `@McpTool`, with
  untrusted-document delimiters; streamable HTTP at `/mcp` and stdio mode verified with a real
  JSON-RPC probe (initialize → tools/list → list_repositories).
- `docs/MCP_SETUP.md` with Claude Desktop stdio config and a generic HTTP client example.
- API keys: SHA-256 hashes in `api_key`, env bootstrap, revocation, fail-closed 401 for `/api/*` and
  `/mcp`, actuator exempt. Request-ID filter (validated header, UUID fallback, MDC + response header).
- Token bucket (agent-written under delegated CHECKPOINT 3): lock-based, injectable clock, ask vs
  search vs general scopes, per key with IP fallback; 429 + `Retry-After` + `RateLimit-*` headers.
- Migration V2 stores document text for `get_document` (20k char cap).
- 24 new tests (178 total): token-bucket concurrency/refill, filter auth/request-id, 429 behaviour and
  scope isolation, MCP client integration for all four tools and 401 on the MCP endpoint.

**Checkpoint provenance:** HUMAN CHECKPOINT 3 (token bucket) is agent-written under the owner's
delegation (ADR-0016); do not present it as human-written without review.

## Phase 5 — Observability, evaluation, load (complete)

- Micrometer metrics for ask/retrieval/LLM/cache/rate-limit/ingest with histograms; Prometheus
  scrape works; Grafana dashboard committed under `deploy/grafana/dashboards/`.
- Evaluation harness (`./mvnw -Peval verify`): 52 cases over `encode/httpx`, `Kludex/starlette`,
  and an injection fixture; real retrieval metrics and structural answer checks. Result:
  hybrid hit@6 92.5% vs vector-only 85.0%; report in `eval/reports/2026-11-15.md` and `docs/EVAL.md`.
- k6 v2.4.0 installed user-local; `load/search.js`, `ask_stubbed.js`, `ask_real.js`.
- Benchmarks in `docs/BENCHMARKS.md`: 200-VU search ~274 req/s at p95 664–804 ms; cache hit path
  808 req/s vs 53 req/s uncached (fake LLM 300 ms); retrieval vector p95 156.6 ms, keyword 134.2 ms,
  fuse ≤1 ms; ingestion 12.03 s at concurrency 2 vs 11.15 s at 8.
- **Virtual threads did not improve DB-bound search** (within noise); recorded honestly.
- 179 tests in the default build (eval test excluded); coverage gates pass.

**Checkpoint provenance:** CHECKPOINT 4 (eval cases) and CHECKPOINT 5 (benchmark interpretation) are
agent-written under the owner's delegation (ADR-0016). Review before quoting as human work.

## Phase 6 — Ship (in progress: packaging complete, deploy/demo pending owner)

- Version bumped to `0.1.0`; production Dockerfile: multi-stage, JRE 25, non-root uid 1001,
  read-only rootfs, no-new-privileges, healthcheck, ONNX cache volume, layered `jar + lib/` cache.
- `docker compose up --build` runs app + Postgres/pgvector + Prometheus + Grafana; app verified
  healthy in-container, `uid=1001(app)`, read-only rootfs, API key enforced (401/200).
- CI: tests + gitleaks + Docker build; GHCR publish on `main` and `v*` tags; non-root smoke check;
  Dependabot for Maven/Actions/Docker.
- Deploy: `deploy/docker-compose.prod.yml` (no public DB/observability ports), Caddy TLS config,
  seed script and demo-key instructions in `deploy/README.md`.
- Docs: README architecture diagram/results/limitations/roadmap, `docs/DEMO.md` + `scripts/demo.sh`,
  `SECURITY.md`, `CONTRIBUTING.md`, MIT `LICENSE`, `RELEASE_NOTES.md`.
- **Pending owner:** host/domain + LLM key for the public demo, demo GIF (checkpoint 6 recording),
  and GitHub repo/push/release `v0.1.0` (no git remote configured).
- CHECKPOINT 6 results/demo prose is agent-written under the delegation; the video is not.

## Polish pass (post-Phase 6)

- Per-request access log with the validated request id in MDC; verified `requestId` appears in ECS
  JSON logs in both host and container runs (actuator requests excluded to avoid probe spam).
- `/actuator/info` exposes build artifact/version via the Spring Boot build-info goal.
- Fixed streaming LLM latency being recorded under the retrieval metric; it now uses
  `repolens_llm_latency`.
- API key hash comparison uses `MessageDigest.isEqual` (constant-time) after the indexed lookup.
- Added `docs/API.md` (payloads, errors, rate-limit headers, SSE events); README badges, default
  demo-key warning, documentation index updated.
- Runtime-verified: Prometheus scrapes both compose and host targets (`up`), Grafana has the
  10-panel RepoLens Overview dashboard, container logs include request IDs.
- New tests: build-info, Prometheus endpoint, rate-limit rejection counter, MCP document truncation.
- Configured and smoke-tested a real Groq provider (`openai/gpt-oss-120b`) via `.env` (never
  committed): grounded install answer with citations (~1.9 s) and a correct refusal.
- Fixed a real-provider bug found during that test: a refusal with an empty `answer` field returned
  the raw JSON as the answer text; the parser now keeps it empty and the verifier returns the
  standard refusal sentence. Covered by parser and ask-service tests.

## Phase A — Real answer-quality eval (complete)

- Grew the eval set to **72 cases** across `encode/httpx`, `Kludex/starlette`, `encode/httpcore` and
  a malicious-document fixture: 48 factual, 8 multi-hop, 12 unanswerable, 4 injection. Every case is
  marked `drafted_by=agent`, `review_status=needs_human_review`.
- Harness now runs against a real OpenAI-compatible provider, reports per-case outcomes, and uses a
  committed LLM-judge prompt (`eval/judge-prompt.md`) with a separate judge model.
- Real run: `openai/gpt-oss-20b` answers + `qwen/qwen3.8-27b` judge (two independent Groq free-tier
  quotas). **0 answer errors**; citation validity 100%; false refusals 2/56 (3.6%); refusal
  precision 85.7%; refusal recall 100% (12/12); injection blocked 100% (4/4); grounded 90% on 20
  judged answers, average 4.70/5.
- Retrieval (56 cases): hybrid hit@6 **92.9%**, vector 87.5%, keyword 51.8%; MRR 0.715 vs 0.699 vs
  0.432. Hybrid beats vector by a real but modest 5.4 points at hit@6. Chunk variants (300/500/800)
  all 92.9%.
- Fixes driven by the first real run (before → after): 8 → 0 provider/JSON errors, 12.5% → 3.6%
  false refusals, 66.7% → 85.7% refusal precision.
  - retry provider JSON-validation 400s and 429/5xx with bounded backoff;
  - configurable 2048 output tokens (800 truncated gpt-oss JSON generation);
  - answer eval uses top-k 5 instead of 3 so multi-hop questions are not starved;
  - separate judge model so quotas do not collide; per-case diagnostic table in reports.
- Free-tier constraints discovered and documented: Groq 8k TPM and 200k tokens/day/model; Google AI
  Studio free tier only 20 requests/day. Reports record models and quotas.
- Default build: 188 tests green, coverage gates pass.

## Phase B — Performance honesty (complete)

- Profiled the 200-VU search path: requests were queued on a 10-connection Hikari pool while Postgres
  used ~18 cores; each request made 4 DB calls. Idle vector search was a 2.4 ms seq scan of 967
  chunks; the ONNX embedding cache was negligible (0.06 s for 7,221 hits).
- Fixes: API-key auth cache (60 s, configurable), positive repository cache, configurable Hikari pool
  (20), and a dedicated vector pool using pgvector iterative HNSW. Plain forced HNSW was slower
  (16 ms) and underfilled; iterative scan returned all 30 rows in 0.85 ms.
- Measured medians of 3 runs each (same 967-chunk repo, host near idle):
  - baseline pool 10 / no auth cache: **127.7 req/s, p95 1,527 ms** (VT); 121.2 req/s, p95 1,530 (PT)
  - + auth/repo cache, pool 20: 156.1 req/s, p95 1,152 ms (VT)
  - + iterative-HNSW vector pool: **2,281.5 req/s, p95 4 ms** (VT); 2,272.7 req/s, p95 4–5 ms (PT)
- Retrieval after: vector p95 3.1 ms, keyword 2.1 ms, fuse 1.0 ms. Main pool acquire 0.003 ms.
- **Virtual threads do not help search** (DB-bound); difference is noise before and after. They are
  expected to matter for the LLM path and ingestion.
- Caveat recorded: shared host; final runs near idle after stopping an unrelated dinesense stack.
- `docs/BENCHMARKS.md` rewritten with machine specs, commands, spreads, before/after and caveats.
- 188 tests green.

## Phase C — README and repo polish (complete)

- Added a real 30-second terminal demo to the README (`docs/demo.gif`, source `docs/demo.cast`,
  recorded from `scripts/demo.sh` with Groq and a full SSE stream) and a one-line measured-results
  summary under the title. No stale "API (planned)" section remained.
- Limitations updated to distinguish measured (retrieval/answer quality, hot-path benchmarks, demo)
  from unmeasured/pending (public URL, frontier-model quality, multi-node).
- Hardening: dev Compose binds app/Postgres/Prometheus/Grafana to `127.0.0.1`; Grafana no longer
  defaults to `admin/admin` (fallback `local-dev-only-change-me`, `.env.example` leaves it empty);
  production Compose exposes only Caddy 80/443; API key + rate limits remain on; container remains
  non-root/read-only. `.env.example` completed with pool sizes, auth-cache TTL, deployment vars.
- Fresh-clone test passed in an isolated Compose project with no `.env`: readiness 200 after ONNX
  warmup, 401 without API key, 200 with the local demo key, Grafana rejects `admin/admin` and accepts
  the new fallback, Prometheus scrapes the app (`up`).
- `SECURITY.md`/`CONTRIBUTING.md` present; added five issue-ready "good first issue" entries to
  `docs/BACKLOG.md` (delete repo, repo pagination, `/ask` deadline, key admin API, OpenTelemetry).
- 188 tests green; ADR-0031 records the binding/password policy.

## Phase D — Study guide (complete)

- `docs/STUDY_GUIDE.md`: zero-to-project explanation of RAG, embeddings, pgvector, chunking, hybrid
  search, RRF with a worked numeric example, `/ask`, citations/refusal/injection, caches, virtual
  threads/pinning, token buckets, MCP, security, observability, tests/eval/benchmarks, plus a
  concept-to-file map.
- `docs/READING_ORDER.md`: 15 core files in reading order, what to look for in each, and a suggested
  four-day pace.
- `docs/INTERVIEW_QA.md`: 30 project-specific questions with honest short answers, including all 10
  from MASTERPLAN Appendix C and the measured caveats (20b model, agent-drafted cases, virtual
  threads not helping DB-bound search).
- `docs/RELEARN_THE_CHECKPOINTS.md`: plain explanations plus five exercises each for RankFusion,
  TokenBucket, eval cases and benchmarks. No solutions, so the owner can rewrite/extend them.
- README documentation index updated. No runtime code changed; 188 tests remain green.

**Known issues:**
- Issue ingestion is capped at 300 by design.
- Vector search always returns nearest neighbours, so a query with no lexical match still returns
  semantic candidates; the eval set accounts for this.
- Real-provider answer quality (including injection resistance) is unverified until a key is provided.
- Rate limiting is per node (in-process Caffeine bucket registry); Redis is a stretch goal.
- Benchmarks are single runs on a shared desktop; ±10% is noise.
