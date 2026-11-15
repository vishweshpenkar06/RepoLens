#!/usr/bin/env bash
# Two-minute terminal demo. Start the stack first:
#   docker compose up -d
#   REPOLENS_API_KEY=<key> ./mvnw spring-boot:run   (or use the container on :8081)
# Then: BASE_URL=http://localhost:8081 REPOLENS_API_KEY=<key> ./scripts/demo.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
API_KEY="${REPOLENS_API_KEY:?set REPOLENS_API_KEY}"
REPO_ID="${REPO_ID:-1}"
AUTH=(-H "X-API-Key: $API_KEY")

section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

section "1. Health"
curl -fsS "$BASE_URL/actuator/health/readiness"; echo

section "2. Repositories"
curl -fsS "${AUTH[@]}" "$BASE_URL/api/v1/repos" | python3 -m json.tool | head -30

section "3. Hybrid search (vector + FTS, RRF)"
curl -fsS "${AUTH[@]}" \
  "$BASE_URL/api/v1/repos/$REPO_ID/search?q=install&k=3" | python3 -m json.tool | head -50

section "4. Grounded answer with citations"
curl -fsS -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  "$BASE_URL/api/v1/repos/$REPO_ID/ask" \
  -d '{"question":"How do I install this project?","topK":6}' | python3 -m json.tool

section "5. SSE streaming"
curl -fsSN "${AUTH[@]}" \
  "$BASE_URL/api/v1/repos/$REPO_ID/ask/stream?question=How%20do%20I%20get%20started%3F&topK=3" \
  | head -20

section "6. MCP tools"
cat <<'TXT'
Tools: list_repositories, search_docs, ask_repo, get_document
HTTP:  /mcp  (streamable HTTP, requires X-API-Key)
stdio: java -jar repolens-0.1.0.jar --spring.main.web-application-type=none \
         --spring.ai.mcp.server.stdio=true --logging.config=classpath:logback-stdio.xml
       See docs/MCP_SETUP.md for the Claude Desktop config.
TXT
