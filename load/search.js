import http from "k6/http";
import { check, sleep } from "k6";

// Ramp to 200 concurrent virtual users against hybrid search.
// Usage:
//   k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=... -e REPO_ID=1 load/search.js

export const options = {
  scenarios: {
    search: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "15s", target: 100 },
        { duration: "30s", target: 200 },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

const QUERIES = [
  "how to install",
  "docker compose",
  "timeouts",
  "authentication",
  "logging",
  "http2",
  "how to fork",
];

export default function () {
  const query = QUERIES[(__VU + __ITER) % QUERIES.length];
  const response = http.get(
    `${__ENV.BASE_URL}/api/v1/repos/${__ENV.REPO_ID}/search?q=${encodeURIComponent(query)}&k=6`,
    { headers: { "X-API-Key": __ENV.API_KEY } },
  );
  check(response, { "search 200": (r) => r.status === 200 });
  sleep(0.05);
}
