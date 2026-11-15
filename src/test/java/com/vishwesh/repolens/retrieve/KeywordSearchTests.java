package com.vishwesh.repolens.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

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
class KeywordSearchTests {

  @Autowired private KeywordSearch keywordSearch;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
  }

  @Test
  void matchesAndRanksByRelevance() {
    long repo = repositories.insert("acme", "demo");
    long docker = seed(repo, "docs/docker.md", "Docker", "docker docker compose compose up");
    long plain = seed(repo, "docs/plain.md", "Plain", "docker compose up");
    seed(repo, "docs/k8s.md", "Kubernetes", "kubernetes cluster setup");

    var results = keywordSearch.search(repo, "docker compose", 10);

    assertThat(results).extracting(ScoredChunk::chunkId).containsExactly(docker, plain);
    assertThat(results.getFirst().score()).isPositive();
  }

  @Test
  void excludesNonMatchingDocuments() {
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/a.md", "A", "docker compose", vector());

    assertThat(keywordSearch.search(repo, "nonexistentterm", 10)).isEmpty();
  }

  @Test
  void filtersByRepository() {
    long repo = repositories.insert("acme", "demo");
    long other = repositories.insert("other", "project");
    long mine = seed(repo, "docs/a.md", "A", "docker compose", vector());
    seed(other, "docs/b.md", "B", "docker compose", vector());

    assertThat(keywordSearch.search(repo, "docker", 10))
        .extracting(ScoredChunk::chunkId)
        .containsExactly(mine);
  }

  @Test
  void respectsLimit() {
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/a.md", "A", "docker compose one", vector());
    seed(repo, "docs/b.md", "B", "docker compose two", vector());

    assertThat(keywordSearch.search(repo, "docker", 1)).hasSize(1);
    assertThat(keywordSearch.search(repo, "docker", 0)).isEmpty();
    assertThat(keywordSearch.search(repo, "  ", 10)).isEmpty();
  }

  @Test
  void supportsQuotedPhrases() {
    long repo = repositories.insert("acme", "demo");
    long phrase = seed(repo, "docs/a.md", "A", "run docker compose now", vector());
    seed(repo, "docs/b.md", "B", "docker is not compose", vector());

    var results = keywordSearch.search(repo, "\"docker compose\"", 10);

    assertThat(results).extracting(ScoredChunk::chunkId).containsExactly(phrase);
  }

  private long seed(long repoId, String path, String title, String content, float[]... ignored) {
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
    chunks.insert(documentId, repoId, chunk, vector());
    return jdbc.sql("SELECT id FROM chunk WHERE document_id = ?")
        .param(documentId)
        .query(Long.class)
        .single();
  }

  private static float[] vector() {
    float[] vector = new float[384];
    vector[0] = 1;
    return vector;
  }
}
