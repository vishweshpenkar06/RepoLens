package com.vishwesh.repoprism.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for RepoPrism. Every value can be overridden through environment variables
 * (see {@code .env.example}) or {@code application.yml}.
 */
@ConfigurationProperties(prefix = "repoprism")
public record RepoPrismProperties(
    @DefaultValue Github github,
    @DefaultValue Embedding embedding,
    @DefaultValue Retrieval retrieval,
    @DefaultValue Ask ask,
    @DefaultValue Llm llm,
    @DefaultValue Cache cache) {

  /** Defaults for tests that build properties by hand with the first four groups. */
  public static Llm defaultLlm() {
    return new Llm(
        "fake",
        "https://api.openai.com/v1",
        "",
        "",
        Duration.ofSeconds(60),
        800,
        0.0,
        true,
        Duration.ZERO);
  }

  public static Cache defaultCache() {
    return new Cache(Duration.ofHours(1), 10_000, Duration.ofHours(24), 10_000);
  }

  public record Github(
      @DefaultValue("") String token,
      @DefaultValue("https://api.github.com") String baseUrl,
      @DefaultValue("true") boolean issuesEnabled,
      @DefaultValue("300") int issueLimit,
      @DefaultValue("1048576") int maxFileBytes) {}

  public record Embedding(
      @DefaultValue("2") int concurrency,
      @DefaultValue("32") int batchSize,
      @DefaultValue("384") int dimensions,
      @DefaultValue("true") boolean warmup) {}

  public record Retrieval(
      @DefaultValue("30") int candidates,
      @DefaultValue("6") int topK,
      @DefaultValue("60") int rrfK) {}

  public record Ask(
      @DefaultValue("20s") Duration timeout,
      @DefaultValue("1000") int maxQuestionLength,
      @DefaultValue("12") int topKMax) {}

  public record Llm(
      @DefaultValue("fake") String provider,
      @DefaultValue("https://api.openai.com/v1") String baseUrl,
      @DefaultValue("") String apiKey,
      @DefaultValue("") String model,
      @DefaultValue("60s") Duration timeout,
      @DefaultValue("800") int maxTokens,
      @DefaultValue("0.0") double temperature,
      @DefaultValue("true") boolean jsonMode,
      @DefaultValue("0ms") Duration fakeLatency) {}

  public record Cache(
      @DefaultValue("1h") Duration answerTtl,
      @DefaultValue("10000") long answerMaxSize,
      @DefaultValue("24h") Duration embeddingTtl,
      @DefaultValue("10000") long embeddingMaxSize) {}
}
