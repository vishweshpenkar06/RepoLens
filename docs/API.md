# REST API

Base path: `/api/v1`. All requests need `X-API-Key` (except `/actuator/health` and
`/actuator/prometheus`). Errors are RFC 7807 `application/problem+json`.

Headers:

| Header | Direction | Meaning |
|---|---|---|
| `X-API-Key` | request | required for `/api/*` and `/mcp` |
| `X-Request-Id` | request/response | optional `[A-Za-z0-9._-]{1,128}`; generated when absent |
| `RateLimit-Limit` / `Remaining` / `Reset` / `Scope` | response | token-bucket state |
| `Retry-After` | 429 response | seconds until the next token |

## Repositories

### `POST /repos` → 202

```json
{"url": "https://github.com/encode/httpx"}
```

```json
{"repoId": 1, "jobId": 1, "status": "PENDING"}
```

Only `https://github.com/{owner}/{name}` is accepted (SSRF guard). Ingestion is asynchronous;
poll the repo for status. Duplicate repository → 409.

### `GET /repos` → 200

```json
[{
  "id": 1, "owner": "encode", "name": "httpx", "slug": "encode/httpx",
  "status": "READY", "defaultBranch": "master",
  "lastIngestedAt": "2026-11-15T11:35:53Z", "contentVersion": "198c...",
  "createdAt": "2026-11-15T11:33:56Z",
  "latestJob": {"id": 2, "status": "SUCCEEDED", "docsSeen": 26, "chunksWritten": 353,
                "startedAt": "...", "finishedAt": "...", "error": null}
}]
```

`status`: `PENDING | INGESTING | READY | FAILED`.

### `GET /repos/{id}` → 200 / 404

Same object as a list entry.

### `POST /repos/{id}/reindex` → 202

Starts an incremental job (ETag + content hash; unchanged documents are not re-embedded). Returns
`{repoId, jobId, status}`. 409 while another job for the repo is running.

## Search

### `GET /repos/{id}/search?q=&k=` → 200

`q` required, max 1,000 chars; `k` clamped 1..12 (default 6). Hybrid vector + full-text results
fused with RRF (k=60).

```json
[{
  "chunkId": 12, "documentId": 4, "kind": "DOC", "pathOrRef": "docs/quickstart.md",
  "url": "https://github.com/encode/httpx/blob/master/docs/quickstart.md",
  "title": "Quickstart", "headingPath": "Quickstart",
  "snippet": "Make a request with...", "score": 0.0322,
  "vectorRank": 1, "keywordRank": 3
}]
```

`vectorRank` / `keywordRank` are 1-based and `null` when that retriever did not return the chunk.

## Answers

### `POST /repos/{id}/ask` → 200

```json
{"question": "How do I make a GET request?", "topK": 6}
```

```json
{
  "answer": "Use `httpx.get(...)` [1].",
  "citations": [{"index": 1, "title": "Quickstart", "url": "https://github.com/...",
                 "headingPath": "Quickstart", "snippet": "..."}],
  "refused": false,
  "latencyMs": 420,
  "cached": false
}
```

`topK` is clamped 1..12. `refused=true` means the model or verifier found insufficient evidence;
`citations` is then empty. Invalid citation markers are stripped; a system-prompt leak forces a
refusal. Answers are cached by `(repoId, contentVersion, normalizedQuestion, topK)`.

### `GET /repos/{id}/ask/stream?question=&topK=` → `text/event-stream`

Events:

```
event:token
data:Use

event:token
data:`httpx.get(...)`

event:final
data:{"answer":"...","citations":[...],"refused":false,"latencyMs":420,"cached":false}
```

The final event is authoritative: if verification refuses, discard streamed tokens and use the
final payload.

## Errors

| Status | When |
|---|---|
| 400 | invalid repo URL, blank/overlong question or query, malformed JSON |
| 401 | missing/invalid/revoked `X-API-Key` |
| 404 | unknown repository or document |
| 409 | duplicate repo, concurrent ingestion |
| 429 | rate limit exceeded (`Retry-After`, `RateLimit-*`) |
| 502 | LLM provider failure |
| 503 | retrieval timeout/failure |

Problem body:

```json
{"type":"about:blank","title":"Invalid repository URL","status":400,
 "detail":"Only https://github.com URLs are allowed","instance":"/api/v1/repos"}
```

## MCP

The same capabilities are exposed as MCP tools over `/mcp` (streamable HTTP, `X-API-Key` required)
and stdio. Tool inputs/outputs and client configs: `docs/MCP_SETUP.md`.
