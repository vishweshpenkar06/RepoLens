#!/usr/bin/env bash
# Pre-load the demo instance with a few repositories. Requires: curl, python3.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
API_KEY="${REPOPRISM_API_KEY:?set REPOPRISM_API_KEY}"
REPOS=("${@:-encode/httpx Kludex/starlette octocat/Hello-World}")

for repo in ${REPOS[*]}; do
  echo "==> $repo"
  body="$(curl -sS -X POST "$BASE_URL/api/v1/repos" \
    -H "X-API-Key: $API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"url\":\"https://github.com/$repo\"}")"
  echo "$body"
  id="$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["repoId"])')"
  for _ in $(seq 1 180); do
    state="$(curl -sS "$BASE_URL/api/v1/repos/$id" -H "X-API-Key: $API_KEY")"
    status="$(printf '%s' "$state" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
    if [ "$status" = "READY" ] || [ "$status" = "FAILED" ]; then
      printf '%s\n' "$state" | python3 -m json.tool
      break
    fi
    sleep 2
  done
done
