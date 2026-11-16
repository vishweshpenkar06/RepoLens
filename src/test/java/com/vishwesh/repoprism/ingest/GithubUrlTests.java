package com.vishwesh.repoprism.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GithubUrlTests {

  @Test
  void buildsSlugAndHtmlUrl() {
    GithubUrl url = new GithubUrl("acme", "demo.repo");
    assertThat(url.slug()).isEqualTo("acme/demo.repo");
    assertThat(url.htmlUrl()).isEqualTo("https://github.com/acme/demo.repo");
  }

  @ParameterizedTest
  @ValueSource(strings = {"-bad", "bad-", "has space", "owner_", ""})
  void rejectsInvalidOwner(String owner) {
    assertThatThrownBy(() -> new GithubUrl(owner, "demo"))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".", "..", "has space", "repo/extra"})
  void rejectsInvalidName(String name) {
    assertThatThrownBy(() -> new GithubUrl("acme", name))
        .isInstanceOf(InvalidRepositoryUrlException.class);
  }
}
