# RepoLens v0.1.0

Self-hosted Java service that ingests GitHub documentation and issues, answers questions with
verified citations, and exposes the same capabilities as an MCP server.

## Highlights

- Hybrid retrieval: pgvector cosine + PostgreSQL full-text, fused with Reciprocal Rank Fusion (k=60).
- Grounded answers with `[n]` citations, refusal on missing evidence, and prompt-injection guards.
- Local ONNX embeddings (all-MiniLM-L6-v2, 384 dims) — no paid embedding key.
- Pluggable LLM: deterministic fake, or any OpenAI-compatible endpoint (OpenAI, OpenRouter, Groq,
  vLLM, LM Studio, Ollama `/v1`).
- REST (`/api/v1`) + MCP over streamable HTTP and stdio with four tools.
- Incremental ingestion via ETag + content hash; answer cache invalidated by content version.
- Hashed API keys, request IDs, per-scope token-bucket rate limiting.
- Prometheus metrics, Grafana dashboard, evaluation harness, k6 load tests.
- Production image: non-root, read-only rootfs, healthcheck, layered dependency caching.

## Measured results

- Retrieval hit@6: **92.5% hybrid** vs 85.0% vector-only vs 65.0% keyword-only (52 cases).
- MRR@6 hybrid: 0.765.
- Search: ~274 req/s at 200 VUs, p95 664–804 ms on a 20-thread laptop CPU.
- `/ask` cache hit path: 808 req/s, p95 6 ms (fake LLM 300 ms; uncached 53 req/s, p95 400 ms).
- Ingestion: 301 docs / 319 chunks in 12.0 s.

## Known limitations

- Answer-quality and real-provider injection resistance are unmeasured until an LLM key is configured.
- Rate limiting and caches are per node.
- Issue ingestion is capped at 300; `get_document` output capped at 20,000 chars.
- Public demo instance is not live yet (needs host/domain and a provider key).
- Human checkpoints were delegated to the agent (ADR-0016); review before presenting as human work.

## Upgrade / run

```bash
docker compose up -d
export REPOLENS_API_KEY=$(openssl rand -hex 32)
curl -s -X POST localhost:8081/api/v1/repos -H "X-API-Key: $REPOLENS_API_KEY" \
  -H 'Content-Type: application/json' -d '{"url":"https://github.com/encode/httpx"}'
```

See `README.md`, `docs/MCP_SETUP.md`, `docs/EVAL.md`, `docs/BENCHMARKS.md`.
