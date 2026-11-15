package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.config.RepoLensProperties;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Hybrid rank fusion for the two retriever result lists.
 *
 * <h2>HUMAN CHECKPOINT 2 — delegated by the owner to the agent</h2>
 *
 * <p><strong>Provenance:</strong> the owner delegated this checkpoint to the agent on 2026-11-15.
 * This implementation is agent-written; see {@code PROGRESS.md}. The contract tests in {@code
 * RankFusionContractTests} validate it.
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
 * <p>The contract tests run as part of the default build.
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
    if (limit <= 0) {
      return List.of();
    }
    List<ScoredChunk> vector = vectorResults == null ? List.of() : vectorResults;
    List<ScoredChunk> keyword = keywordResults == null ? List.of() : keywordResults;
    if (vector.isEmpty() && keyword.isEmpty()) {
      return List.of();
    }

    Map<Long, Integer> vectorRanks = ranks(vector);
    Map<Long, Integer> keywordRanks = ranks(keyword);
    Map<Long, ScoredChunk> payloads = new HashMap<>();
    for (ScoredChunk chunk : keyword) {
      payloads.putIfAbsent(chunk.chunkId(), chunk);
    }
    for (ScoredChunk chunk : vector) {
      payloads.put(chunk.chunkId(), chunk);
    }

    return payloads.keySet().stream()
        .map(chunkId -> {
          Integer vectorRank = vectorRanks.get(chunkId);
          Integer keywordRank = keywordRanks.get(chunkId);
          double score = reciprocal(vectorRank) + reciprocal(keywordRank);
          return new FusedHit(payloads.get(chunkId), score, vectorRank, keywordRank);
        })
        .sorted(
            Comparator.comparingDouble(FusedHit::fusedScore)
                .reversed()
                .thenComparing(hit -> hit.chunk().chunkId()))
        .limit(limit)
        .toList();
  }

  private Map<Long, Integer> ranks(List<ScoredChunk> results) {
    Map<Long, Integer> ranks = new LinkedHashMap<>();
    for (ScoredChunk chunk : results) {
      ranks.putIfAbsent(chunk.chunkId(), ranks.size() + 1);
    }
    return ranks;
  }

  private double reciprocal(Integer rank) {
    return rank == null ? 0.0 : 1.0 / (k + rank);
  }
}
