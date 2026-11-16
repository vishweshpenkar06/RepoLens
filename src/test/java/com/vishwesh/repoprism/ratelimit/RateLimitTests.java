package com.vishwesh.repoprism.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vishwesh.repoprism.TestcontainersConfiguration;
import com.vishwesh.repoprism.config.RateLimitProperties;
import com.vishwesh.repoprism.ingest.Chunk;
import com.vishwesh.repoprism.ingest.DocumentKind;
import com.vishwesh.repoprism.ingest.DocumentSource;
import com.vishwesh.repoprism.ingest.MarkdownChunker;
import com.vishwesh.repoprism.ingest.store.ChunkStore;
import com.vishwesh.repoprism.ingest.store.DocumentStore;
import com.vishwesh.repoprism.ingest.store.RepositoryStore;
import com.vishwesh.repoprism.support.FakeEmbedder;
import com.vishwesh.repoprism.support.TestFakes;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.ai.model.embedding=none",
      "repoprism.embedding.warmup=false",
      "repoprism.security.api-key-required=false",
      "repoprism.rate-limit.enabled=true",
      "repoprism.rate-limit.ask-capacity=1",
      "repoprism.rate-limit.ask-refill-per-second=0.0001",
      "repoprism.rate-limit.search-capacity=1",
      "repoprism.rate-limit.search-refill-per-second=0.0001",
      "repoprism.rate-limit.general-capacity=100"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestFakes.class})
class RateLimitTests {

  @Autowired private MockMvc mvc;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job, query_log RESTART IDENTITY CASCADE")
        .update();
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
  void rejectsWhenAskBucketIsEmpty() throws Exception {
    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"docker compose\"}")
                .with(request -> {
                  request.setRemoteAddr("10.0.0.1");
                  return request;
                }))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "1"))
        .andExpect(header().string("RateLimit-Scope", "ASK"));

    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"docker compose\"}")
                .with(request -> {
                  request.setRemoteAddr("10.0.0.1");
                  return request;
                }))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("RateLimit-Scope", "ASK"));

    assertThat(meterRegistry.get("repoprism.ratelimit.rejected").counter().count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void scopesHaveSeparateBuckets() throws Exception {
    mvc.perform(
            post("/api/v1/repos/1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"docker compose\"}")
                .with(request -> {
                  request.setRemoteAddr("10.0.0.2");
                  return request;
                }))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/repos/1/search")
                .param("q", "docker compose")
                .with(request -> {
                  request.setRemoteAddr("10.0.0.2");
                  return request;
                }))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Scope", "SEARCH"));
  }

  @Test
  void bucketInstancesArePerIdentityAndScope() {
    RateLimitService service =
        new RateLimitService(new RateLimitProperties(true, 1, 1, 1, 1, 1, 1));

    TokenBucket a = service.bucket("key:1", RateLimitScope.ASK);
    TokenBucket again = service.bucket("key:1", RateLimitScope.ASK);
    TokenBucket otherKey = service.bucket("key:2", RateLimitScope.ASK);
    TokenBucket otherScope = service.bucket("key:1", RateLimitScope.SEARCH);

    assertThat(a).isSameAs(again);
    assertThat(a).isNotSameAs(otherKey);
    assertThat(a).isNotSameAs(otherScope);
  }
}
