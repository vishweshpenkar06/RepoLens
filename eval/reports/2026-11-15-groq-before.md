# RepoLens evaluation report — 2026-11-15

- Provider: `groq`
- Model: `openai/gpt-oss-120b`
- Repositories: encode/httpx, Kludex/starlette, encode/httpcore + injection fixture
- Cases: 72 — 48 factual, 8 multi-hop, 12 unanswerable, 4 injection (all agent-drafted, needs human review)

## Retrieval

| Mode | hit@3 | hit@6 | MRR@6 | cases |
|---|---|---|---|---|
| hybrid (RRF) | 85.7% | 92.9% | 0.705 | 56 |
| vector only | 83.9% | 87.5% | 0.699 | 56 |
| keyword only | 46.4% | 51.8% | 0.432 | 56 |

## Answers

| Metric | Value |
|---|---|
| Answerable cases | 48 |
| Citation validity (valid citations / all) | 100.0% |
| Answers with only valid citations | 87.5% |
| False refusal rate (answerable) | 12.5% |
| Refusal precision | 66.7% |
| Refusal recall (unanswerable) | 100.0% |
| Unanswerable cases answered anyway | 0 / 12 |
| Injection blocked (no system leak) | 100.0% |
| Injection leaks | 0 |
| Answer errors | 8 |
| Groundedness judged | 20 cases (max 20) |
| Grounded rate | 90.0% |
| Average judge score (1-5) | 4.70 |

## Chunk-size variants (hybrid hit@6)

| Target tokens | hit@6 | cases | chunks indexed |
|---|---|---|---|
| 300 | 92.9% | 56 | 1077 |
| 500 | 92.9% | 56 | 981 |
| 800 | 92.9% | 56 | 948 |

## Notes

- Repositories are ingested without issues; retrieval metrics use real data and the local ONNX MiniLM embedder.
- Answer top-k is 3; retrieval metrics use the product default (6).
- Judge sample is limited to 20 non-refused answerable cases; calls are paced for free-tier rate limits.
- All cases are agent-drafted (`review_status=needs_human_review`).
