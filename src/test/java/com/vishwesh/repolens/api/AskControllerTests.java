package com.vishwesh.repolens.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.answer.AskCache;
import com.vishwesh.repolens.answer.FakeLlmGateway;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "spring.ai.model.embedding=none",
      "repolens.embedding.warmup=false",
      "repolens.security.api-key-required=false"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestFakes.class})
class AskControllerTests {

  @Autowired private MockMvc mvc;
  @Autowired private FakeLlmGateway llm;
  @Autowired private AskCache cache;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job, query_log RESTART IDENTITY CASCADE")
        .update();
    cache.invalidateAll();
    llm.reset();
    long repo = repositories.insert("acme", "demo");
    long documentId =
        documents.upsert(
            repo,
            new DocumentSource(
                DocumentKind.DOC,
                "docs/docker.md",
                "https://github.com/acme/demo/blob/main/docs/docker.md",
                "Docker",
                "docker compose",
                "\"e1\""),
            "h1");
    chunks.insert(
        documentId,
        repo,
        new Chunk(0, "Docker", "docker compose", MarkdownChunker.estimateTokens("docker compose")),
        FakeEmbedder.vectorFor("docker compose"));
  }

  @Test
  void answersWithCitations() throws Exception {
    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"docker compose\",\"topK\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refused").value(false))
        .andExpect(jsonPath("$.cached").value(false))
        .andExpect(jsonPath("$.citations[0].index").value(1))
        .andExpect(jsonPath("$.citations[0].url").value(containsString("docs/docker.md")))
        .andExpect(jsonPath("$.answer").value(containsString("[1]")));
  }

  @Test
  void returnsCachedAnswerOnRepeatQuestion() throws Exception {
    String body = "{\"question\":\"docker compose\"}";
    mvc.perform(post("/api/v1/repos/1/ask").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    mvc.perform(post("/api/v1/repos/1/ask").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cached").value(true));
  }

  @Test
  void rejectsBlankAndOverlongQuestions() throws Exception {
    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + "x".repeat(1001) + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returnsNotFoundForUnknownRepository() throws Exception {
    mvc.perform(
            post("/api/v1/repos/999/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"docker\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void streamsTokensAndFinalEvent() throws Exception {
    MvcResult result =
        mvc.perform(get("/api/v1/repos/1/ask/stream").param("question", "docker compose"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("event:token")))
        .andExpect(content().string(containsString("event:final")))
        .andExpect(content().string(containsString("\"citations\"")));
  }
}
