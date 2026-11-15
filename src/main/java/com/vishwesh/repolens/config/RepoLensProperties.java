package com.vishwesh.repolens.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for RepoLens. Every value can be overridden through environment variables
 * (see {@code .env.example}) or {@code application.yml}.
 */
@ConfigurationProperties(prefix = "repolens")
public record RepoLensProperties(
    @DefaultValue Github github,
    @DefaultValue Embedding embedding,
    @DefaultValue Retrieval retrieval,
    @DefaultValue Ask ask) {

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
}
