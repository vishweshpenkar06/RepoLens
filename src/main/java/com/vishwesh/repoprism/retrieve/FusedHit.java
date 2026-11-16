package com.vishwesh.repoprism.retrieve;

/**
 * A chunk after rank fusion.
 *
 * @param chunk the chunk payload and its per-retriever score (the score is informational; fusion
 *     uses ranks)
 * @param fusedScore the reciprocal-rank-fusion score
 * @param vectorRank 1-based rank in the vector result list, or {@code null} if absent
 * @param keywordRank 1-based rank in the keyword result list, or {@code null} if absent
 */
public record FusedHit(
    ScoredChunk chunk, double fusedScore, Integer vectorRank, Integer keywordRank) {}
