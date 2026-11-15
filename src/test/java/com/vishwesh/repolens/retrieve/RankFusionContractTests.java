package com.vishwesh.repolens.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vishwesh.repolens.config.RepoLensProperties;
import com.vishwesh.repolens.ingest.DocumentKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract for Reciprocal Rank Fusion; runs in the default build. */
class RankFusionContractTests {

  private static final double EPSILON = 1e-9;

  private final RankFusion fusion = new RankFusion(propertiesWithK(60));

  @Test
  void exposesConfiguredK() {
    assertThat(fusion.k()).isEqualTo(60);
    assertThat(new RankFusion(propertiesWithK(10)).k()).isEqualTo(10);
  }

  @Test
  void scoresDocumentFoundByBothRetrieversAtRankOne() {
    ScoredChunk a = chunk(1);

    List<FusedHit> hits = fusion.fuse(List.of(a), List.of(a), 10);

    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().fusedScore()).isCloseTo(2.0 / 61.0, within(EPSILON));
    assertThat(hits.getFirst().vectorRank()).isEqualTo(1);
    assertThat(hits.getFirst().keywordRank()).isEqualTo(1);
  }

  @Test
  void scoresDocumentFoundByOneRetriever() {
    ScoredChunk a = chunk(1);

    List<FusedHit> vectorOnly = fusion.fuse(List.of(a), List.of(), 10);
    List<FusedHit> keywordOnly = fusion.fuse(List.of(), List.of(a), 10);

    assertThat(vectorOnly.getFirst().fusedScore()).isCloseTo(1.0 / 61.0, within(EPSILON));
    assertThat(vectorOnly.getFirst().keywordRank()).isNull();
    assertThat(keywordOnly.getFirst().fusedScore()).isCloseTo(1.0 / 61.0, within(EPSILON));
    assertThat(keywordOnly.getFirst().vectorRank()).isNull();
  }

  @Test
  void returnsUnionOfBothLists() {
    ScoredChunk a = chunk(1);
    ScoredChunk b = chunk(2);
    ScoredChunk c = chunk(3);

    List<FusedHit> hits = fusion.fuse(List.of(a, b), List.of(c, a), 10);

    assertThat(hits).extracting(hit -> hit.chunk().chunkId()).containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  @Test
  void ranksByFusedScoreThenChunkId() {
    ScoredChunk vectorFirst = chunk(1);
    ScoredChunk shared = chunk(2);
    ScoredChunk keywordFirst = chunk(3);

    List<FusedHit> hits =
        fusion.fuse(List.of(shared, vectorFirst), List.of(shared, keywordFirst), 10);

    // shared: 1/61 + 1/61 wins; the other two tie at 1/62 and break on chunkId ascending.
    assertThat(hits).extracting(hit -> hit.chunk().chunkId()).containsExactly(2L, 1L, 3L);
    assertThat(hits.get(1).fusedScore()).isCloseTo(1.0 / 62.0, within(EPSILON));
  }

  @Test
  void handlesMissingDocumentsAndPreservesRanks() {
    ScoredChunk a = chunk(1);
    ScoredChunk b = chunk(2);

    List<FusedHit> hits = fusion.fuse(List.of(a, b), List.of(), 10);

    assertThat(hits.get(0).vectorRank()).isEqualTo(1);
    assertThat(hits.get(1).vectorRank()).isEqualTo(2);
    assertThat(hits.get(0).keywordRank()).isNull();
  }

  @Test
  void deDuplicatesWithinOneListAtTheBestRank() {
    ScoredChunk a = chunk(1);
    ScoredChunk b = chunk(2);

    List<FusedHit> hits = fusion.fuse(List.of(a, a, b), List.of(), 10);

    assertThat(hits).hasSize(2);
    assertThat(hits.get(0).chunk().chunkId()).isEqualTo(1L);
    assertThat(hits.get(0).vectorRank()).isEqualTo(1);
    assertThat(hits.get(1).chunk().chunkId()).isEqualTo(2L);
    assertThat(hits.get(1).vectorRank()).isEqualTo(2);
  }

  @Test
  void respectsLimit() {
    List<ScoredChunk> vector = List.of(chunk(1), chunk(2), chunk(3), chunk(4));

    assertThat(fusion.fuse(vector, List.of(), 2)).hasSize(2);
    assertThat(fusion.fuse(vector, List.of(), 0)).isEmpty();
    assertThat(fusion.fuse(vector, List.of(), -1)).isEmpty();
  }

  @Test
  void returnsEmptyForEmptyInputs() {
    assertThat(fusion.fuse(List.of(), List.of(), 10)).isEmpty();
  }

  @Test
  void doesNotMutateInputs() {
    List<ScoredChunk> vector = new ArrayList<>(List.of(chunk(2), chunk(1)));
    List<ScoredChunk> keyword = new ArrayList<>(List.of(chunk(1)));
    List<ScoredChunk> vectorCopy = List.copyOf(vector);
    List<ScoredChunk> keywordCopy = List.copyOf(keyword);

    fusion.fuse(vector, keyword, 10);

    assertThat(vector).containsExactlyElementsOf(vectorCopy);
    assertThat(keyword).containsExactlyElementsOf(keywordCopy);
  }

  @Test
  void usesVectorPayloadWhenPresentInBothLists() {
    ScoredChunk vectorPayload =
        new ScoredChunk(7, 1, DocumentKind.DOC, "docs/a.md", "u", "t", "h", "vector", 0.9);
    ScoredChunk keywordPayload =
        new ScoredChunk(7, 1, DocumentKind.DOC, "docs/a.md", "u", "t", "h", "keyword", 2.0);

    List<FusedHit> hits = fusion.fuse(List.of(vectorPayload), List.of(keywordPayload), 10);

    assertThat(hits.getFirst().chunk().content()).isEqualTo("vector");
  }

  private static ScoredChunk chunk(long id) {
    return new ScoredChunk(
        id,
        id,
        DocumentKind.DOC,
        "docs/" + id + ".md",
        "https://example/" + id,
        "t",
        "h",
        "content " + id,
        1.0);
  }

  private static RepoLensProperties propertiesWithK(int k) {
    return new RepoLensProperties(
        new RepoLensProperties.Github("", "https://api.github.com", true, 300, 1_048_576),
        new RepoLensProperties.Embedding(2, 32, 384, false),
        new RepoLensProperties.Retrieval(30, 6, k),
        new RepoLensProperties.Ask(Duration.ofSeconds(20), 1000, 12));
  }
}
