package com.vishwesh.repolens.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RepoLensPropertiesTests {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(TestConfig.class)
          .withPropertyValues("repolens.github.base-url=https://api.github.com");

  @Test
  void bindsDocumentedDefaults() {
    runner.run(context -> {
      RepoLensProperties properties = context.getBean(RepoLensProperties.class);
      assertThat(properties.github().token()).isEmpty();
      assertThat(properties.github().baseUrl()).isEqualTo("https://api.github.com");
      assertThat(properties.embedding().concurrency()).isEqualTo(2);
      assertThat(properties.embedding().batchSize()).isEqualTo(32);
      assertThat(properties.embedding().dimensions()).isEqualTo(384);
      assertThat(properties.retrieval().candidates()).isEqualTo(30);
      assertThat(properties.retrieval().topK()).isEqualTo(6);
      assertThat(properties.retrieval().rrfK()).isEqualTo(60);
      assertThat(properties.ask().timeout()).isEqualTo(Duration.ofSeconds(20));
      assertThat(properties.ask().maxQuestionLength()).isEqualTo(1000);
      assertThat(properties.ask().topKMax()).isEqualTo(12);
    });
  }

  @Test
  void overridesFromProperties() {
    runner
        .withPropertyValues(
            "repolens.embedding.concurrency=8",
            "repolens.retrieval.top-k=10",
            "repolens.ask.timeout=5s")
        .run(context -> {
          RepoLensProperties properties = context.getBean(RepoLensProperties.class);
          assertThat(properties.embedding().concurrency()).isEqualTo(8);
          assertThat(properties.retrieval().topK()).isEqualTo(10);
          assertThat(properties.ask().timeout()).isEqualTo(Duration.ofSeconds(5));
        });
  }

  @EnableConfigurationProperties(RepoLensProperties.class)
  static class TestConfig {}
}
