import http from "k6/http";
import { check, sleep } from "k6";

// /ask against the deterministic fake LLM (~300 ms simulated latency), so the LLM provider is not
// measured. Run the app with --repolens.llm.fake-latency=300ms.
// Usage:
//   k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=... -e REPO_ID=1 load/ask_stubbed.js
//   MODE=unique (default) avoids the answer cache; MODE=fixed measures the cache hit path.

export const options = {
  scenarios: {
    ask: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<2000"],
  },
};

export default function () {
  const mode = __ENV.MODE || "unique";
  const question =
    mode === "fixed"
      ? "hello world"
      : `hello world request ${__VU}-${__ITER}`;
  const response = http.post(
    `${__ENV.BASE_URL}/api/v1/repos/${__ENV.REPO_ID}/ask`,
    JSON.stringify({ question, topK: 3 }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": __ENV.API_KEY,
      },
    },
  );
  check(response, { "ask 200": (r) => r.status === 200 });
  sleep(0.02);
}
