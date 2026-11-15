package com.vishwesh.repolens.retrieve;

public record SearchResult(
    long chunkId,
    long documentId,
    String kind,
    String pathOrRef,
    String url,
    String title,
    String headingPath,
    String snippet,
    double score,
    Integer vectorRank,
    Integer keywordRank) {

  public static SearchResult from(FusedHit hit) {
    ScoredChunk chunk = hit.chunk();
    return new SearchResult(
        chunk.chunkId(),
        chunk.documentId(),
        chunk.kind().name(),
        chunk.pathOrRef(),
        chunk.url(),
        chunk.title(),
        chunk.headingPath(),
        snippet(chunk.content()),
        hit.fusedScore(),
        hit.vectorRank(),
        hit.keywordRank());
  }

  private static String snippet(String content) {
    String normalized = content == null ? "" : content.replaceAll("\\s+", " ").strip();
    int max = 400;
    if (normalized.length() <= max) {
      return normalized;
    }
    return normalized.substring(0, max) + "...";
  }
}
