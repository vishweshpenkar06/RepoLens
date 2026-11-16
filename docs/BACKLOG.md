# Backlog

Ideas outside the MASTERPLAN. Do not implement until v0.1.0 ships.

- Redis-backed distributed cache and rate limiter (MASTERPLAN stretch goal).
- Cross-encoder reranker and its measured effect (stretch goal).
- GitHub webhook re-index on push (stretch goal).
- Cross-repo search.
- Incremental chunk embedding (skip unchanged chunk hashes).
- OpenAPI spec generation for the REST API.
- Per-tenant quotas and usage reporting.

## Good first issues (issue-ready)

### 1. Add a `DELETE /api/v1/repos/{id}` endpoint
**Context:** the data model already cascades from `repository`; only the REST endpoint is missing.
**Acceptance:** 204 on success, RFC 7807 404 for unknown ids, test with Testcontainers proving
documents/chunks/jobs are removed, README/API docs updated.

### 2. Add pagination to `GET /api/v1/repos`
**Context:** the list is unbounded and returns the latest job for every repo.
**Acceptance:** `page`/`size` (max 100) query params, stable ordering by id, response includes
`total`, existing tests updated, no N+1 regression.

### 3. Make `/ask` enforce the configured end-to-end deadline
**Context:** `repoprism.ask.timeout` exists but retrieval stages (15 s) and the LLM client enforce
their own separate timeouts.
**Acceptance:** a deadline covers retrieval + LLM + verification; timeout returns 504 ProblemDetail;
a test uses a slow fake LLM to prove the bound.

### 4. Add an admin API for API keys
**Context:** keys are bootstrapped from env or inserted with SQL; revocation requires SQL.
**Acceptance:** `POST /admin/keys` returns the raw key once, stores only its SHA-256 hash, supports
label + revoke, admin calls require an existing valid key, tests cover hashing/revocation/cache TTL.

### 5. Emit OpenTelemetry traces for `/ask` and ingestion
**Context:** metrics and JSON logs exist, but there are no traces across retrieval/LLM/DB.
**Acceptance:** OTLP exporter config, spans for retrieve/vector/keyword/fuse/LLM/verify, trace id in
logs and ProblemDetails, disabled by default, docs + docker-compose collector example.
