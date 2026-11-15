import http from "k6/http";
import { check, sleep } from "k6";

// Small sanity run against a real LLM provider. Set LLM_PROVIDER/LLM_API_KEY/LLM_MODEL on the app.
// Usage:
//   k6 run -e BASE_URL=http://localhost:8081 -e API_KEY=... -e REPO_ID=1 load/ask_real.js

export const options = {
  vus: 2,
  iterations: 10,
  thresholds: {
    http_req_failed: ["rate<0.05"],
  },
};

const QUESTIONS = [
  "What is this repository about?",
  "How do I install the project?",
  "How do I run the tests?",
];

export default function () {
  const question = QUESTIONS[__ITER % QUESTIONS.length];
  const response = http.post(
    `${__ENV.BASE_URL}/api/v1/repos/${__ENV.REPO_ID}/ask`,
    JSON.stringify({ question, topK: 6 }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": __ENV.API_KEY,
      },
      timeout: "120s",
    },
  );
  check(response, { "ask 200": (r) => r.status === 200 });
  sleep(1);
}
