package com.vishwesh.repolens.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.ingest.Chunk;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.DocumentSource;
import com.vishwesh.repolens.ingest.MarkdownChunker;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.vishwesh.repolens.ingest.store.DocumentStore;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import com.vishwesh.repolens.support.FakeEmbedder;
import com.vishwesh.repolens.support.TestFakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.ai.model.embedding=none", "repolens.embedding.warmup=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestFakes.class})
class SearchControllerTests {

  @Autowired private MockMvc mvc;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;
  @Autowired private FakeEmbedder embedder;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/docker.md", "Docker", "docker compose");
    seed(repo, "docs/k8s.md", "Kubernetes", "kubernetes cluster");
  }

  @Test
  void returnsHybridResultsWithRanksAndMetadata() throws Exception {
    mvc.perform(get("/api/v1/repos/1/search").param("q", "docker compose"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].pathOrRef").value("docs/docker.md"))
        .andExpect(jsonPath("$[0].vectorRank").value(1))
        .andExpect(jsonPath("$[0].keywordRank").value(1))
        .andExpect(
            jsonPath("$[0].url").value("https://github.com/acme/demo/blob/main/docs/docker.md"))
        .andExpect(jsonPath("$[0].snippet").value("docker compose"))
        .andExpect(jsonPath("$[0].kind").value("DOC"));
  }

  @Test
  void clampsRequestedLimitToMaximum() throws Exception {
    long repo = 1L;
    for (int i = 0; i < 15; i++) {
      seed(repo, "docs/extra-" + i + ".md", "Extra " + i, "docker compose extra " + i);
    }

    mvc.perform(get("/api/v1/repos/1/search").param("q", "docker compose").param("k", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(12));
  }

  @Test
  void returnsEmptyListForRepositoryWithoutChunks() throws Exception {
    repositories.insert("empty", "repo");

    mvc.perform(get("/api/v1/repos/2/search").param("q", "docker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void rejectsBlankOrMissingQuery() throws Exception {
    mvc.perform(get("/api/v1/repos/1/search").param("q", "  "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid search query"));

    mvc.perform(get("/api/v1/repos/1/search"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid search query"));
  }

  @Test
  void rejectsOverlongQuery() throws Exception {
    mvc.perform(get("/api/v1/repos/1/search").param("q", "x".repeat(1001)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returnsNotFoundForUnknownRepository() throws Exception {
    mvc.perform(get("/api/v1/repos/999/search").param("q", "docker"))
        .andExpect(status().isNotFound());
  }

  private void seed(long repoId, String path, String title, String content) {
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
    chunks.insert(documentId, repoId, chunk, FakeEmbedder.vectorFor(content));
  }
}
