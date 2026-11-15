# Security

## Reporting a vulnerability

Open a private GitHub security advisory or email the maintainer. Please do not open a public issue
with exploit details. You can expect an acknowledgement within 7 days.

## Supported versions

`v0.1.x` receives security fixes. Older versions are not supported.

## Security model

| Area | Control |
|---|---|
| API access | `X-API-Key` required for `/api/*` and `/mcp`; SHA-256 hashes stored in `api_key`; revoked keys rejected; fail-closed by default |
| Web exposure | `/actuator/health` public; `/actuator/prometheus` exempt from the key but must be protected at the network layer |
| SSRF | only `https://github.com/{owner}/{name}`, public-IP resolution, no credentials/ports/query, no redirects |
| Prompt injection | retrieved text delimited and labelled untrusted; no tools in the answer step; citation validation; system-sentinel leak detection |
| Rate limiting | token bucket per API key (IP fallback), separate ask/search/general scopes, 429 + `Retry-After` |
| Secrets | never committed; `.env` ignored; only hashes of API keys stored; logs do not include keys or prompts at INFO |
| Container | non-root `uid 1001`, read-only root filesystem, `no-new-privileges`, healthcheck |
| Dependencies | Dependabot (Maven, Actions, Docker) and a gitleaks scan in CI |

## Known limitations

- The default LLM is a deterministic fake; real-provider prompt-injection resistance is measured
  only when a provider key is configured (see `docs/EVAL.md`).
- Rate limiting and caches are per node; multi-node deployments need a shared limiter/cache.
- The demo instance, if deployed, must use a rotated key and tight rate limits.
- `get_document` returns at most 20,000 characters; ingestion caps documents at 1 MiB.

## Hardening checklist for deployment

- Set `REPOLENS_API_KEY` to a random value (`openssl rand -hex 32`) and never commit it.
- Put TLS in front of the app (Caddy/nginx/load balancer) and expose only 443.
- Do not publish the Postgres, Prometheus, or Grafana ports.
- Keep `REPOLENS_API_KEY_REQUIRED=true` and rate limiting enabled.
- Rotate keys by inserting a new hash and setting `revoked=true` on the old row.
