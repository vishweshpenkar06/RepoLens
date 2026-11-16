# Demo

The reproducible terminal demo is `scripts/demo.sh`. It exercises health, repository listing,
hybrid search, a grounded answer with citations, SSE streaming, and prints the MCP entry points.

```bash
cp .env.example .env                 # set REPOPRISM_API_KEY
docker compose up -d
REPOPRISM_API_KEY=<key> ./mvnw spring-boot:run
# in another terminal
BASE_URL=http://localhost:8081 REPOPRISM_API_KEY=<key> REPO_ID=1 ./scripts/demo.sh
```

First ingest a repository if the list is empty:

```bash
curl -s -X POST localhost:8081/api/v1/repos \
  -H "X-API-Key: $REPOPRISM_API_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://github.com/encode/httpx"}'
```

## Recording status

HUMAN CHECKPOINT 6 (demo video/GIF + final README results) was delegated to the agent. The demo was
recorded with `asciinema record` and rendered with `agg` into `docs/demo.gif` (source
`docs/demo.cast`) from the live service. The recording is reproducible with the commands in this
file; regenerate it after changing the demo script.

## What to show (90 seconds)

1. `docker compose ps` — app, Postgres+pgvector, Prometheus, Grafana.
2. `scripts/demo.sh` steps 2–4: ingest status, ranked hybrid search, cited answer.
3. SSE step: tokens arrive incrementally, final event carries citations.
4. `docs/MCP_SETUP.md` + Claude Desktop calling `ask_repo` over stdio.
5. `docs/EVAL.md` table: hybrid 92.5% hit@6 vs vector 85.0%.
6. `docs/BENCHMARKS.md`: cache hit 808 vs 53 req/s; virtual threads did not help DB-bound search.
