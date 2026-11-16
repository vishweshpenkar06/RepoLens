package com.vishwesh.repoprism.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestFakes {

  @Bean
  @Primary
  FakeEmbedder fakeEmbedder() {
    return new FakeEmbedder();
  }

  @Bean
  @Primary
  FakeGitHubClient fakeGitHubClient() {
    return new FakeGitHubClient();
  }
}
