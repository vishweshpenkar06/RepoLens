package com.vishwesh.repolens.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.ingest.Chunk;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.DocumentSource;
import com.vishwesh.repolens.ingest.MarkdownChunker;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.vishwesh.repolens.ingest.store.DocumentStore;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import com.vishwesh.repolens.support.TestFakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.ai.model.embedding=none", "repolens.embedding.warmup=false"})
@Import({TestcontainersConfiguration.class, TestFakes.class})
class VectorSearchTests {

  private static final int DIMS = 384;

  @Autowired private VectorSearch vectorSearch;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
  }

  @Test
  void ordersByCosineSimilarity() {
    long repo = repositories.insert("acme", "demo");
    long alpha = seed(repo, "docs/alpha.md", "Alpha", "alpha content", e(1, 0, 0));
    long beta = seed(repo, "docs/beta.md", "Beta", "beta content", e(0, 1, 0));
    long mixed = seed(repo, "docs/mixed.md", "Mixed", "mixed content", e(0.7, 0.7, 0));

    var results = vectorSearch.search(repo, e(1, 0, 0), 10);

    assertThat(results).extracting(ScoredChunk::chunkId).containsExactly(alpha, mixed, beta);
    assertThat(results.getFirst().score()).isCloseTo(1.0, within(1e-6));
    assertThat(results.get(1).score()).isCloseTo(0.7071, within(1e-3));
  }

  @Test
  void filtersByRepository() {
    long repo = repositories.insert("acme", "demo");
    long other = repositories.insert("other", "project");
    long mine = seed(repo, "docs/a.md", "A", "mine", e(1, 0, 0));
    seed(other, "docs/b.md", "B", "theirs", e(1, 0, 0));

    var results = vectorSearch.search(repo, e(1, 0, 0), 10);

    assertThat(results).extracting(ScoredChunk::chunkId).containsExactly(mine);
  }

  @Test
  void respectsLimit() {
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/a.md", "A", "a", e(1, 0, 0));
    seed(repo, "docs/b.md", "B", "b", e(0.9, 0.1, 0));

    assertThat(vectorSearch.search(repo, e(1, 0, 0), 1)).hasSize(1);
    assertThat(vectorSearch.search(repo, e(1, 0, 0), 0)).isEmpty();
  }

  @Test
  void returnsSourceMetadata() {
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/setup.md", "Setup", "install it", e(1, 0, 0));

    ScoredChunk chunk = vectorSearch.search(repo, e(1, 0, 0), 1).getFirst();

    assertThat(chunk.url()).isEqualTo("https://github.com/acme/demo/blob/main/docs/setup.md");
    assertThat(chunk.title()).isEqualTo("Setup");
    assertThat(chunk.pathOrRef()).isEqualTo("docs/setup.md");
    assertThat(chunk.kind()).isEqualTo(DocumentKind.DOC);
    assertThat(chunk.headingPath()).isEqualTo("Setup");
  }

  @Test
  void returnsEmptyForUnknownRepository() {
    assertThat(vectorSearch.search(999, e(1, 0, 0), 10)).isEmpty();
    assertThat(vectorSearch.search(1, null, 10)).isEmpty();
  }

  private long seed(long repoId, String path, String title, String content, float[] embedding) {
    long documentId =
        documents.upsert(
            repoId,
            new DocumentSource(
                DocumentKind.DOC,
                path,
                "https://github.com/acme/demo/blob/main/" + path,
                title,
                content,
                "\"etag-" + path + "\""),
            "hash-" + path);
    Chunk chunk = new Chunk(0, title, content, MarkdownChunker.estimateTokens(content));
    chunks.insert(documentId, repoId, chunk, embedding);
    return jdbc.sql("SELECT id FROM chunk WHERE document_id = ?")
        .param(documentId)
        .query(Long.class)
        .single();
  }

  private static float[] e(double... head) {
    float[] vector = new float[DIMS];
    for (int i = 0; i < head.length; i++) {
      vector[i] = (float) head[i];
    }
    return vector;
  }
}
