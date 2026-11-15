package com.vishwesh.repolens.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vishwesh.repolens.config.RepoLensProperties;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.support.FakeEmbedder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrieverTests {

  private final VectorSearch vectorSearch = mock(VectorSearch.class);
  private final KeywordSearch keywordSearch = mock(KeywordSearch.class);
  private final RepoLensProperties properties = properties();
  private final Retriever retriever =
      new Retriever(
          vectorSearch, keywordSearch, new RankFusion(properties), new FakeEmbedder(), properties);

  @Test
  void fusesParallelRetrieverResults() {
    ScoredChunk vectorFirst = chunk(1);
    ScoredChunk keywordFirst = chunk(2);
    when(vectorSearch.search(eq(1L), any(float[].class), eq(30)))
        .thenReturn(List.of(vectorFirst, keywordFirst));
    when(keywordSearch.search(eq(1L), eq("query"), eq(30))).thenReturn(List.of(keywordFirst));

    List<FusedHit> hits = retriever.retrieve(1, "query", 5);

    assertThat(hits).hasSize(2);
    assertThat(hits.get(0).fusedScore()).isGreaterThan(hits.get(1).fusedScore());
    assertThat(hits.getFirst().chunk().chunkId()).isEqualTo(2L);
    assertThat(hits.getFirst().vectorRank()).isEqualTo(2);
    assertThat(hits.getFirst().keywordRank()).isEqualTo(1);
    verify(vectorSearch).search(eq(1L), any(float[].class), eq(30));
    verify(keywordSearch).search(eq(1L), eq("query"), eq(30));
  }

  @Test
  void clampsLimit() {
    assertThat(retriever.clampLimit(0)).isEqualTo(6);
    assertThat(retriever.clampLimit(-1)).isEqualTo(6);
    assertThat(retriever.clampLimit(3)).isEqualTo(3);
    assertThat(retriever.clampLimit(50)).isEqualTo(12);
  }

  @Test
  void propagatesRetrieverFailures() {
    when(vectorSearch.search(eq(1L), any(float[].class), anyInt()))
        .thenThrow(new IllegalStateException("vector down"));
    when(keywordSearch.search(eq(1L), eq("query"), anyInt())).thenReturn(List.of());

    assertThatThrownBy(() -> retriever.retrieve(1, "query", 5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vector down");
  }

  private static ScoredChunk chunk(long id) {
    return new ScoredChunk(
        id, id, DocumentKind.DOC, "docs/" + id + ".md", "u" + id, "t", "h", "c", 1.0);
  }

  private static RepoLensProperties properties() {
    return new RepoLensProperties(
        new RepoLensProperties.Github("", "https://api.github.com", true, 300, 1_048_576),
        new RepoLensProperties.Embedding(2, 32, 384, false),
        new RepoLensProperties.Retrieval(30, 6, 60),
        new RepoLensProperties.Ask(Duration.ofSeconds(20), 1000, 12),
        RepoLensProperties.defaultLlm(),
        RepoLensProperties.defaultCache());
  }
}
