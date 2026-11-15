# Evaluation

RepoLens ships an evaluation harness because "it feels right" is not evidence. The harness lives in
`eval/` and `src/test/java/com/vishwesh/repolens/eval/`. Provenance: HUMAN CHECKPOINT 4 (curating
cases) was delegated to the agent (ADR-0016); the questions are agent-written and should be reviewed
before being quoted as human curation.

## Cases

`eval/cases.jsonl` contains **52 cases** over real repositories:

- `encode/httpx` — 20 factual + 3 multi-hop
- `Kludex/starlette` — 16 factual + 1 multi-hop
- `eval/injection-fixture` — synthetic repository with a malicious document, 4 injection cases
- 8 unanswerable questions spread across the real repositories

Each line:

```json
{"id":"c002","repo":"encode/httpx","question":"How do I make a GET request with HTTPX?",
 "expected_sources":["docs/quickstart.md"],"type":"factual","notes":""}
```

`expected_sources` are document paths (`path_or_ref`) that a good retriever should return;
matching is exact on the path, case-insensitive.

## Run

```bash
./mvnw -Peval verify
```

The profile runs only the tagged `eval` test, uses Testcontainers Postgres, ingests the two repos
with real ONNX embeddings (set `GITHUB_TOKEN` to avoid rate limits), runs all modes, rebuilds chunks
for the chunk-size variants, and writes `eval/reports/<date>.md`.

## Metrics

Retrieval (factual + multi-hop):

- **hit@3**, **hit@6**: at least one expected source in the top k.
- **MRR@6**: reciprocal rank of the first expected source.

Answer metrics are structural with the default fake LLM:

- citation validity (answerable answers have non-empty, resolvable citations),
- false refusal rate on answerable cases,
- refusal recall on unanswerable cases,
- injection blocked (a scripted leak of the system sentinel must be refused).

Chunk-size variants rebuild chunks from stored document text at 300/500/800 target tokens and report
hybrid hit@6.

## Latest results (2026-11-15)

| Mode | hit@3 | hit@6 | MRR@6 |
|---|---|---|---|
| vector only | 82.5% | 85.0% | 0.688 |
| keyword only | 57.5% | 65.0% | 0.530 |
| hybrid (RRF) | **87.5%** | **92.5%** | **0.765** |

Chunk variants all reached 92.5% hit@6 (300: 898 chunks, 500: 819, 800: 792). Full report:
`eval/reports/2026-11-15.md`.

## Limitations

- Answer quality is not systematically measured against a real model yet; the fake model
  deliberately returns a fixed grounded answer. A live smoke test with Groq
  `openai/gpt-oss-120b` produced grounded citations and a correct refusal; running the full case set
  against a real provider is the next step.
- 52 cases is enough to compare retrieval modes but not a statistically strong benchmark.
- Expected sources are curated by the agent; a human should audit them before quoting the numbers.
