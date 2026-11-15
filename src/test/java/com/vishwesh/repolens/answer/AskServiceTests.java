package com.vishwesh.repolens.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.api.RepositoryNotFoundException;
import com.vishwesh.repolens.ingest.Chunk;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.DocumentSource;
import com.vishwesh.repolens.ingest.MarkdownChunker;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.vishwesh.repolens.ingest.store.DocumentStore;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import com.vishwesh.repolens.support.FakeEmbedder;
import com.vishwesh.repolens.support.TestFakes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.ai.model.embedding=none", "repolens.embedding.warmup=false"})
@Import({TestcontainersConfiguration.class, TestFakes.class})
class AskServiceTests {

  @Autowired private AskService askService;
  @Autowired private FakeLlmGateway llm;
  @Autowired private AskCache cache;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job, query_log RESTART IDENTITY CASCADE")
        .update();
    cache.invalidateAll();
    llm.reset();
    long repo = repositories.insert("acme", "demo");
    seed(repo, "docs/docker.md", "Docker", "docker compose");
  }

  @Test
  void returnsGroundedAnswerWithValidCitations() {
    AnswerResult result = askService.ask(1, "docker compose", null);

    assertThat(result.refused()).isFalse();
    assertThat(result.cached()).isFalse();
    assertThat(result.answer()).contains("[1]");
    assertThat(result.citations()).hasSize(1);
    assertThat(result.citations().getFirst().url()).contains("docs/docker.md");
    assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    assertThat(llm.callCount()).isEqualTo(1);
    assertThat(queryLogCount()).isEqualTo(1);
  }

  @Test
  void cachesAnswersAndNormalizesQuestions() {
    AnswerResult first = askService.ask(1, "docker compose", null);
    AnswerResult second = askService.ask(1, "  Docker   COMPOSE ", null);

    assertThat(first.cached()).isFalse();
    assertThat(second.cached()).isTrue();
    assertThat(second.answer()).isEqualTo(first.answer());
    assertThat(llm.callCount()).isEqualTo(1);
  }

  @Test
  void cacheIsInvalidatedWhenContentVersionChanges() {
    askService.ask(1, "docker compose", null);
    jdbc.sql("UPDATE repository SET content_version = 'v2' WHERE id = 1").update();

    AnswerResult afterReindex = askService.ask(1, "docker compose", null);

    assertThat(afterReindex.cached()).isFalse();
    assertThat(llm.callCount()).isEqualTo(2);
  }

  @Test
  void refusesWhenModelRefuses() {
    llm.enqueueCompletion(
        "{\"answer\":\"I don't know based on the provided context.\",\"citations\":[],\"refused\":true}");

    AnswerResult result = askService.ask(1, "docker compose", null);

    assertThat(result.refused()).isTrue();
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void returnsRefusalTextWhenModelRefusesWithEmptyAnswer() {
    llm.enqueueCompletion("{\"answer\":\"\",\"citations\":[],\"refused\":true}");

    AnswerResult result = askService.ask(1, "docker compose", null);

    assertThat(result.refused()).isTrue();
    assertThat(result.answer()).isEqualTo(CitationVerifier.REFUSAL_ANSWER);
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void refusesWhenAllCitationsAreInvalid() {
    llm.enqueueCompletion("{\"answer\":\"Bold claim [99]\",\"citations\":[99],\"refused\":false}");

    AnswerResult result = askService.ask(1, "docker compose", null);

    assertThat(result.refused()).isTrue();
    assertThat(result.answer()).isEqualTo(CitationVerifier.REFUSAL_ANSWER);
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void refusesWhenModelLeaksTheSystemPrompt() {
    llm.enqueueCompletion(
        "{\"answer\":\"Rules: "
            + PromptBuilder.SYSTEM_SENTINEL
            + " [1]\",\"citations\":[1],\"refused\":false}");

    AnswerResult result = askService.ask(1, "docker compose", null);

    assertThat(result.refused()).isTrue();
  }

  @Test
  void refusesWithoutCallingTheLlmWhenNothingIsRetrieved() {
    repositories.insert("empty", "repo");

    AnswerResult result = askService.ask(2, "anything", null);

    assertThat(result.refused()).isTrue();
    assertThat(result.citations()).isEmpty();
    assertThat(llm.callCount()).isZero();
  }

  @Test
  void rejectsInvalidQuestions() {
    assertThatThrownBy(() -> askService.ask(1, "  ", null))
        .isInstanceOf(InvalidQuestionException.class);
    assertThatThrownBy(() -> askService.ask(1, "x".repeat(1001), null))
        .isInstanceOf(InvalidQuestionException.class);
    assertThatThrownBy(() -> askService.ask(1, null, null))
        .isInstanceOf(InvalidQuestionException.class);
  }

  @Test
  void failsForUnknownRepository() {
    assertThatThrownBy(() -> askService.ask(999, "docker", null))
        .isInstanceOf(RepositoryNotFoundException.class);
  }

  @Test
  void recordsAskAndCacheMetrics() {
    double missesBefore = meterRegistry.get("repolens.cache.misses").counter().count();
    double hitsBefore = meterRegistry.get("repolens.cache.hits").counter().count();
    double timersBefore =
        meterRegistry.find("repolens.ask.latency").timers().stream().mapToLong(Timer::count).sum();

    askService.ask(1, "docker compose", null);
    askService.ask(1, "docker compose", null);

    assertThat(meterRegistry.get("repolens.cache.misses").counter().count())
        .isEqualTo(missesBefore + 1);
    assertThat(meterRegistry.get("repolens.cache.hits").counter().count())
        .isEqualTo(hitsBefore + 1);
    assertThat(
            meterRegistry.find("repolens.ask.latency").timers().stream()
                .mapToLong(Timer::count)
                .sum())
        .isEqualTo((long) timersBefore + 2);
  }

  @Test
  void streamsTokensAndFinalAnswer() {
    StringBuilder tokens = new StringBuilder();
    AtomicReference<AnswerResult> finalResult = new AtomicReference<>();

    askService.stream(1, "docker compose", null, tokens::append, finalResult::set);

    assertThat(tokens.toString()).contains("fake grounded answer");
    assertThat(finalResult.get()).isNotNull();
    assertThat(finalResult.get().refused()).isFalse();
    assertThat(finalResult.get().citations()).hasSize(1);
  }

  @Test
  void streamsCachedAnswerWithoutCallingTheLlmAgain() {
    AnswerResult first = askService.ask(1, "docker compose", null);
    StringBuilder tokens = new StringBuilder();
    AtomicReference<AnswerResult> finalResult = new AtomicReference<>();

    askService.stream(1, "docker compose", null, tokens::append, finalResult::set);

    assertThat(tokens.toString()).isEqualTo(first.answer());
    assertThat(finalResult.get().cached()).isTrue();
    assertThat(llm.callCount()).isEqualTo(1);
  }

  private long queryLogCount() {
    return jdbc.sql("SELECT count(*) FROM query_log").query(Long.class).single();
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
