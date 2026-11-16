# Evaluation

RepoLens ships an evaluation harness because "it feels right" is not evidence. The harness lives in
`eval/` and `src/test/java/com/vishwesh/repolens/eval/`. Provenance: HUMAN CHECKPOINT 4 (curating
cases) was delegated to the agent (ADR-0016). Every case is marked
`"drafted_by": "agent", "review_status": "needs_human_review"` and should be reviewed before being
quoted as human curation.

## Cases

`eval/cases.jsonl` contains **72 cases** across three real repositories plus a synthetic fixture:

| Repository | Cases |
|---|---|
| `encode/httpx` | 27 |
| `Kludex/starlette` | 23 |
| `encode/httpcore` | 18 |
| `eval/injection-fixture` (malicious document) | 4 |

Types: **48 factual**, **8 multi-hop**, **12 unanswerable**, **4 injection**. Retrieval metrics are
computed on the 56 factual + multi-hop cases.

Each line:

```json
{"id":"c002","repo":"encode/httpx","question":"How do I make a GET request with HTTPX?",
 "expected_sources":["docs/quickstart.md"],"type":"factual","notes":"",
 "drafted_by":"agent","review_status":"needs_human_review"}
```

`expected_sources` are document paths (`path_or_ref`) that a good retriever should return;
matching is exact on the path, case-insensitive.

## Run

Fake provider (structural answer checks, no key, ~3 min):

```bash
./mvnw -Peval verify
```

Real provider (loads `.env`, set `GITHUB_TOKEN` to avoid GitHub rate limits):

```bash
set -a; source .env; set +a
GITHUB_TOKEN=$(gh auth token) EVAL_DELAY_SECONDS=20 ./mvnw -Peval verify
```

The real run uses the configured `LLM_*` endpoint for answers and a separate Groq model
(`qwen/qwen3.8-27b`) for the judge so their free-tier daily quotas do not collide. It writes
`eval/reports/<date>-<provider>.md`, including per-case outcomes.

## Latest real results (2026-11-16)

Provider `groq`; answer model `openai/gpt-oss-20b`; judge model `qwen/qwen3.8-27b`; answer top-k 5.

### Retrieval (56 cases)

| Mode | hit@3 | hit@6 | MRR@6 |
|---|---|---|---|
| Vector only | 83.9% | 87.5% | 0.699 |
| Keyword only | 46.4% | 51.8% | 0.432 |
| **Hybrid (RRF)** | **85.7%** | **92.9%** | **0.715** |

Hybrid beats vector-only by **5.4 points at hit@6** and **1.8 points at hit@3**. That is a real but
**modest** improvement, not a dramatic one; keyword-only FTS is much weaker on these paraphrased
questions.

### Answers

| Metric | Result |
|---|---|
| Answerable cases | 56 |
| Answer errors (provider/JSON) | **0** |
| False refusals on answerable cases | 2 / 56 = **3.6%** |
| Non-refused answerable cases | 54 / 56 = 96.4% |
| Citation validity (valid citations / all citations) | **100%** |
| Non-refused answers with only valid citations | **100%** (54/54) |
| Refusal precision | 12 / 14 = **85.7%** |
| Refusal recall on unanswerable cases | 12 / 12 = **100%** |
| Injection blocked (no system leak) | 4 / 4 = **100%** |
| Groundedness judged | 20 non-refused answerable cases |
| Grounded rate | **90%** (18/20) |
| Average judge score (1–5) | **4.70** |

The two false refusals are a factual HTTPX question and one multi-hop question; both are cases where
the model judged the retrieved context insufficient. The two lower judge scores were 3 and 2 on
correctly cited answers, i.e. citation validity is not the same as every sentence being fully
supported.

### Chunk-size variants (hybrid hit@6, 56 cases)

| Target tokens | hit@6 | Chunks indexed |
|---|---|---|
| 300 | 92.9% | 1,077 |
| 500 | 92.9% | 981 |
| 800 | 92.9% | 948 |

Chunk size made no difference in hit@6 on this dataset; 800 uses the fewest chunks.

## Before / after the real-provider bug fixes

The first real run exposed two pipeline bugs and one evaluation-design issue. The comparison below
also reflects a model change forced by free-tier quotas, so read it as "pipeline health", not a pure
model comparison.

| Run | Model / settings | Answer errors | False refusals | Refusal precision | Injection blocked | Grounded |
|---|---|---|---|---|---|---|
| Before | gpt-oss-120b, top-k 3, 800 max tokens | 8 / 72 | 6 / 48 (12.5%) | 66.7% | 100% | 90% (20 judged) |
| After | gpt-oss-20b, top-k 5, 2048 max tokens | **0 / 72** | **2 / 56 (3.6%)** | **85.7%** | 100% | 90% (20 judged) |

Fixes shipped for this:

1. **Retry provider JSON-validation 400s.** Groq returns HTTP 400 with `json_validate_failed` when
   the model's JSON document is malformed. The gateway now retries those with bounded backoff.
2. **Raise the output cap.** `LLM_MAX_TOKENS` is configurable and defaults to 2048. At 800, gpt-oss
   reasoning plus JSON often exhausted the completion budget (`max completion tokens reached before
   generating a valid document`).
3. **Use the product context window.** Answer evaluation now requests top-k 5 (retrieval metrics use
   the product default 6); top-k 3 was starving multi-hop questions and inflating false refusals.
4. **Separate the judge model** so answer and judge calls do not consume the same daily token quota.
5. **Per-case outcome table** in the report, so failures can be diagnosed instead of guessed.

## LLM-as-judge

`eval/judge-prompt.md` is the committed judge prompt. The judge receives the question, the numbered
untrusted context, and the answer, then returns `{"grounded": boolean, "score": 1-5, "reason": ...}`.
Scores 4–5 count as grounded. The harness judges a deterministic sample of the first 20 non-refused
answerable cases to stay inside free-tier quotas; this is a sample, not all 56 answers.

## Limitations

- **Models differ from production intent.** `gpt-oss-20b` was used for answers because the 120b
  model's 200k-token daily free quota was exhausted; the judge is a different model. Numbers are real
  but not comparable to a paid frontier model.
- **Answer top-k is 5**, one below the product default of 6, to fit free-tier token budgets.
- **Judge sample is 20 answers**, not all 56.
- **Cases are agent-drafted** and need human review. 56 retrieval-scored cases is enough to compare
  modes but not a statistically strong benchmark.
- **Retrieval metrics use documentation without issues** in the eval DB to keep runs deterministic.
- Free-tier quotas make a full re-run slow (the last run took ~31 minutes). The per-case table and
  model names are recorded so results remain reproducible.
