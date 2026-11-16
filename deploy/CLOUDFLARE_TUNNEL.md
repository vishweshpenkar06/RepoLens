# Temporary deployment: Cloudflare Quick Tunnel + Neon

This is the free, no-card deployment used for the public demo. It is not a permanent host: the URL
exists only while the Java process and `cloudflared` run on the workstation. Hugging Face Docker
Spaces were the first choice but now require a PRO subscription (HTTP 402).

## Components

| Component | Where | Cost |
|---|---|---|
| App | `systemd --user` transient unit `repoprism-demo` on this workstation | $0 |
| Database | Neon free PostgreSQL with pgvector 0.8.7 | $0 |
| Public HTTPS | Cloudflare Quick Tunnel (`cloudflared`) | $0 |
| LLM | Groq free tier (`openai/gpt-oss-20b`) | $0 within quota |

## Secrets (never committed)

Create `~/.config/repoprism-demo.env` with mode 600:

```dotenv
DB_URL=jdbc:postgresql://<neon-host>/neondb?sslmode=require&prepareThreshold=0
DB_USERNAME=<neon-user>
DB_PASSWORD=<neon-password>
REPOPRISM_API_KEY=<random demo key>
REPOPRISM_API_KEY_REQUIRED=true
LLM_PROVIDER=groq
LLM_BASE_URL=https://api.groq.com/openai/v1
LLM_MODEL=openai/gpt-oss-20b
LLM_API_KEY=<groq key>
LLM_MAX_TOKENS=2048
SERVER_PORT=8081
RATE_LIMIT_ENABLED=true
RATE_LIMIT_ASK_CAPACITY=5
RATE_LIMIT_ASK_REFILL=0.05
RATE_LIMIT_SEARCH_CAPACITY=20
RATE_LIMIT_SEARCH_REFILL=2
RATE_LIMIT_GENERAL_CAPACITY=30
RATE_LIMIT_GENERAL_REFILL=1
DB_POOL_SIZE=10
VECTOR_POOL_SIZE=5
GITHUB_ISSUES_ENABLED=false
EMBEDDING_CACHE_DIR=/tmp/repolens-onnx
```

## Start

```bash
systemd-run --user --unit=repoprism-demo --collect \
  --property=EnvironmentFile=/home/vishwesh/.config/repoprism-demo.env \
  --working-directory="$PWD" \
  /usr/lib/jvm/java-25-openjdk/bin/java -jar target/repoprism-0.1.0.jar

systemd-run --user --unit=repoprism-tunnel --collect \
  ~/.local/bin/cloudflared tunnel --url http://localhost:8081 --no-autoupdate

journalctl --user -u repoprism-tunnel --no-pager | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com'
```

Seed a database with `deploy/seed.sh` (set `BASE_URL` and `REPOPRISM_API_KEY`); the demo DB was
seeded with `encode/httpx` and `Kludex/starlette` while issues were disabled.

## Stop

```bash
systemctl --user stop repoprism-demo repoprism-tunnel
```

## Limits and policy

- The Quick Tunnel URL is random and changes on every restart; update the README when it changes.
- The demo API key is public and rate limited; do not reuse it for real data. Ingestion is rate
  limited but still reachable, so do not leave this instance on an untrusted network indefinitely.
- The workstation must stay awake/online for the demo. This is not a production or SLA deployment.
- For permanent free hosting without a card, Tailscale Funnel with a free tailnet is the next option;
  cloud hosts generally require card verification.
