package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.config.RepoLensProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hybrid rank fusion for the two retriever result lists.
 *
 * <h2>HUMAN CHECKPOINT 2 — implemented by the project owner</h2>
 *
 * <p><strong>Do not implement this unless you are the human owner.</strong> The agent must leave
 * this stub untouched. The contract tests in {@code RankFusionContractTests} (tag {@code
 * human-checkpoint}) validate a correct implementation.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>Use Reciprocal Rank Fusion: {@code score(d) = sum over retrievers 1 / (k + rank_i(d))},
 *       with {@code k} taken from {@link #k()} (default 60). Ranks are 1-based.
 *   <li>Inputs are each ranked best-first. The result is the union of chunk ids.
 *   <li>A chunk absent from a list contributes 0 from that list and its rank is {@code null}.
 *   <li>If a chunk id appears more than once in one input list, count it once at its best (lowest)
 *       rank; ranks are assigned after de-duplication, preserving first occurrence.
 *   <li>Sort by {@code fusedScore} descending; break ties by {@code chunkId} ascending (stable,
 *       deterministic output).
 *   <li>Return at most {@code limit} results. {@code limit <= 0} returns an empty list.
 *   <li>Never mutate the input lists.
 *   <li>Preserve the {@link ScoredChunk} payload from whichever list contains it (they are equal
 *       for the same chunk id; prefer the vector payload if both exist).
 * </ul>
 *
 * <h3>Why k = 60</h3>
 *
 * <p>RRF's {@code k} dampens the difference between adjacent ranks: a document ranked first by both
 * retrievers scores {@code 2/61}, while a document ranked first by one and absent from the other
 * scores {@code 1/61}. This rewards agreement without letting a single retriever dominate. See the
 * RRF literature (Cormack et al., 2009) and the project ADR.
 *
 * <p>Run the contract tests with: {@code ./mvnw -Pcheckpoints test}.
 */
@Component
public class RankFusion {

  private final int k;

  public RankFusion(RepoLensProperties properties) {
    this.k = properties.retrieval().rrfK();
  }

  /** The RRF constant; must be {@code >= 0}. */
  public int k() {
    return k;
  }

  /**
   * Fuses the two ranked lists as described in the class Javadoc.
   *
   * @param vectorResults vector-search results, best first (may be empty, never null)
   * @param keywordResults keyword-search results, best first (may be empty, never null)
   * @param limit maximum number of results to return
   * @return fused hits, best first
   */
  public List<FusedHit> fuse(
      List<ScoredChunk> vectorResults, List<ScoredChunk> keywordResults, int limit) {
    throw new UnsupportedOperationException(
        "HUMAN CHECKPOINT 2: implement Reciprocal Rank Fusion in RankFusion#fuse. "
            + "See the Javadoc contract and run ./mvnw -Pcheckpoints test");
  }
}
