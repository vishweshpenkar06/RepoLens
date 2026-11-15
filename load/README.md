# Load tests

Install k6 (`https://grafana.com/docs/k6/latest/set-up/install-k6/`). On this machine:
`~/.local/bin/k6` (v2.4.0, user-local).

Start the app with an API key and rate limiting disabled for measurement:

```bash
export REPOLENS_API_KEY=loadtest-key
export RATE_LIMIT_ENABLED=false
java -jar target/repolens-0.1.0.jar
```

Run:

```bash
k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=1 load/search.js
k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=1 load/ask_stubbed.js
k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=loadtest-key -e REPO_ID=1 load/ask_real.js
```

`ask_stubbed.js` expects `--repolens.llm.fake-latency=300ms`. Use `-e MODE=fixed` for the cache
hit path and `-e MODE=unique` (default) to bypass the answer cache. Results and interpretation are
in `docs/BENCHMARKS.md`.
