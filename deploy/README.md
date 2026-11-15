# Deployment

The deployment target is a small VPS running Docker Compose behind Caddy (ADR-0027). The same
compose file also works on any container host that can run a Postgres+pgvector container.

## 1. Prepare the host

- Ubuntu/Debian VPS with 2 vCPU / 4 GB RAM minimum (the ONNX model is CPU-bound).
- Docker Engine + Compose plugin installed.
- DNS `A`/`AAAA` record pointing your domain at the host.
- Open ports 80 and 443 only.

## 2. Configure

On the server:

```bash
git clone <your-repo-url> repolens && cd repolens
cp .env.example .env
```

Edit `.env`:

```dotenv
DOMAIN=repolens.example.com
REPOLENS_IMAGE=ghcr.io/OWNER/repolens:0.1.0
REPOLENS_API_KEY=<openssl rand -hex 32>
DB_PASSWORD=<random password>
LLM_PROVIDER=openai          # or openrouter, groq, ollama...
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=<provider key>   # fake provider needs no key
LLM_MODEL=<model name>
```

The GHCR package must be public (or log in with `docker login ghcr.io`).

## 3. Start

```bash
docker compose -f deploy/docker-compose.prod.yml up -d
docker compose -f deploy/docker-compose.prod.yml ps
curl -fsS https://$DOMAIN/actuator/health/readiness
```

Caddy obtains TLS automatically. Only `app`, `postgres` and `caddy` run in production; there is no
public Prometheus/Grafana/Postgres port.

## 4. Seed the demo repositories

```bash
BASE_URL=https://$DOMAIN REPOLENS_API_KEY=<key> ./deploy/seed.sh
```

This ingests `encode/httpx`, `Kludex/starlette` and `octocat/Hello-World`. First ingestion downloads
the ONNX model into the `onnxdata` volume.

## 5. Demo API key

Create a separate, rate-limited key for the public demo:

```bash
# generate
DEMO_KEY=$(openssl rand -hex 32)
# store only the hash (label "demo"), then revoke it when the demo ends
HASH=$(printf '%s' "$DEMO_KEY" | sha256sum | cut -d' ' -f1)
docker compose -f deploy/docker-compose.prod.yml exec postgres \
  psql -U repolens -d repolens -c \
  "INSERT INTO api_key (key_hash, label) VALUES ('$HASH', 'demo');"
```

Keep ask limits low (`RATE_LIMIT_ASK_CAPACITY`, `RATE_LIMIT_ASK_REFILL`). Revoke with
`UPDATE api_key SET revoked = true WHERE label = 'demo';`.

## Status

- Image builds locally and runs healthy as non-root (`docs/ENVIRONMENT.md`).
- GHCR publishing is wired in `.github/workflows/ci.yml` on `main` and version tags.
- A public URL is not live yet: it needs a host/domain and provider key from the owner.
