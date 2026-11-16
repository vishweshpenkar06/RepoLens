# Re-learn the human checkpoints

These four parts were originally the human-owned checkpoints. They were delegated to the agent
(ADR-0016), so if you want to own them on your resume you should be able to **rewrite or extend them
from scratch**. Use this file as a workbook: cover the implementation, attempt the exercises, then
compare with the code. No solutions are given on purpose.

---

## 1. RankFusion (RRF)

**File:** `src/main/java/com/vishwesh/repolens/retrieve/RankFusion.java`
**Tests:** `src/test/java/com/vishwesh/repolens/retrieve/RankFusionContractTests.java`
**Concept:** two ranked lists go in (vector results, keyword results); one fused list comes out.
Only **ranks** are used: `score(d) = Σ 1/(k + rank_i(d))`, `k = 60`, ranks 1-based.

Worked example to keep in mind: vector `[A,B,C]`, keyword `[B,C,D]`.

```
B: 1/62 + 1/61 = 0.03252
C: 1/63 + 1/62 = 0.03200
A: 1/61        = 0.01639
D: 1/63        = 0.01587
```

Result: B, C, A, D.

**Exercises**

1. Close the file. On paper, write the algorithm (union, ranks, de-duplication, sort, limit). Then
   implement `fuse` in a scratch class and compare behavior with the contract tests.
2. Add a new test where one list contains the same chunk twice at ranks 1 and 2. Write down the
   expected ranks and score **before** running it. Does your implementation agree with the repo's?
3. Change `k` to 10 and to 200 in a copy. Which documents win when k is small vs large? Explain in
   one paragraph why k=60 is a compromise.
4. Extend RRF to **weighted** RRF: `score = Σ w_i / (k + rank_i)`, with weights 0.7 vector / 0.3
   keyword. Write tests for weights 1/0 (pure vector) and 0/1 (pure keyword).
5. Break the implementation deliberately (skip de-duplication, then skip tie-breaking). Which
   contract test catches each bug? Fix them again without looking at the original.

---

## 2. TokenBucket

**File:** `src/main/java/com/vishwesh/repolens/ratelimit/TokenBucket.java`
**Tests:** `src/test/java/com/vishwesh/repolens/ratelimit/TokenBucketTests.java`
**Concept:** a bucket of `capacity` tokens refills at `refillPerSecond`; each request takes one token;
no token means 429. Time is injected as `LongSupplier` nanoseconds so tests do not sleep.

Example: capacity 3, refill 1/s. Three immediate requests pass; the fourth fails; after 1 s one token
exists, so exactly one request passes.

**Exercises**

1. On paper, write the refill math and the `tryAcquire` state machine. Then implement it in a scratch
   class with a `ReentrantLock`, first without looking at the repo version.
2. Write three tests using a fake clock: initial capacity, refill after 1 s, and "time going
   backwards does not refill". Run them against your implementation.
3. Write a concurrency test: capacity 100, 200 virtual threads calling `tryAcquire` at the same
   instant; exactly 100 must succeed. Explain why `synchronized` would also work on JDK 25 but why the
   project prefers `ReentrantLock`.
4. Add a `tryAcquire(int tokens)` method for weighted requests (e.g. `/ask` costs 2). Define what
   happens when `tokens > capacity` and write tests.
5. Design (no code) a Redis-backed distributed token bucket. How would you make refill atomic? What
   happens when Redis is slow or unavailable? Which consistency tradeoff do you accept?

---

## 3. Evaluation cases

**Files:** `eval/cases.jsonl`, `src/test/java/com/vishwesh/repolens/eval/EvalRunnerTests.java`,
`eval/judge-prompt.md`
**Concept:** cases are JSONL entries `{repo, question, expected_sources[], type}`. Retrieval is scored
by whether an expected source appears in top-k, plus MRR. Answer cases check citations, refusal and
injection. Unanswerable cases have no expected source; injection cases use a malicious fixture.

Current set: 72 cases — 48 factual, 8 multi-hop, 12 unanswerable, 4 injection.

**Exercises**

1. Add 5 factual cases to a real repo you know. Pick exact `expected_sources` paths and verify they
   exist in the repo tree. Ask a human to review them.
2. Add 5 unanswerable questions that are **plausible but not in the docs**. Run the eval; does the
   system refuse? If it answers, inspect the retrieved chunks — did retrieval fail or did the model
   overreach?
3. Write 3 multi-hop cases that need two specific documents. Explain why top-k 3 may be too small and
   why the answer eval uses a different top-k from retrieval metrics.
4. Extend the injection fixture with a second malicious document (different instruction style). What
   would "blocked" mean for an answer that quotes the malicious text without following it?
5. By hand, compute hit@3 and MRR for five cases from a saved report. Then change one expected source
   and see how the numbers move. Write a paragraph on why 56 retrieval cases is not a strong
   statistical benchmark.

---

## 4. Benchmarks

**Files:** `load/search.js`, `load/ask_stubbed.js`, `docs/BENCHMARKS.md`
**Concept:** measure the service, not the LLM: fake chat model with fixed latency for `/ask`; fixed
queries for `/search`. Report medians of repeated runs, not one run. Vary one thing at a time
(threads, pool size, cache, vector strategy).

Current headline (same 967-chunk repo, near-idle host):

- baseline pool 10/no auth cache: 127.7 req/s, p95 1,527 ms
- after auth/repo cache + pool 20: 156.1 req/s, p95 1,152 ms
- after dedicated iterative-HNSW pool: **2,281.5 req/s, p95 4 ms**
- platform threads after fixes: 2,272.7 req/s, p95 4–5 ms

**Exercises**

1. Re-run the search benchmark 3 times with the current code. Record median, min and max. Compare
   with the report; is the machine quiet enough? Write down what makes a run invalid.
2. Run with `DB_POOL_SIZE=10` and `=40` (restart between runs). Does throughput change? Explain using
   the Hikari "pending" and "acquire" metrics, not intuition.
3. Use `EXPLAIN (ANALYZE, BUFFERS)` on the vector query with:
   - default planner,
   - `enable_seqscan=off`,
   - `enable_seqscan=off; enable_bitmapscan=off; hnsw.iterative_scan=relaxed_order`.
   Compare execution time, row counts and CPU. Which plan would you choose for 1,000 vs 100,000
   chunks?
4. Run 3 virtual-thread and 3 platform-thread runs. Compute medians and spreads. Write a short answer
   to: "What did virtual threads change, and where did they not help?"
5. Write the benchmark section of a resume bullet using your numbers. Then rewrite it honestly with
   the caveat about the shared host and the k6 think-time limit. Compare the two versions.
