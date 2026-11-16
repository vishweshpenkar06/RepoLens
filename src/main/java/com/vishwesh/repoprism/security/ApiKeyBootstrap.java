package com.vishwesh.repoprism.security;

import com.vishwesh.repoprism.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the configured API key (hash only) into the database on startup. */
@Component
public class ApiKeyBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrap.class);

  private final ApiKeyService apiKeyService;
  private final SecurityProperties properties;

  public ApiKeyBootstrap(ApiKeyService apiKeyService, SecurityProperties properties) {
    this.apiKeyService = apiKeyService;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    String raw = properties.bootstrapApiKey();
    if (raw != null && !raw.isBlank()) {
      apiKeyService.bootstrap(raw, "bootstrap");
      log.info("Bootstrapped API key from configuration (only its SHA-256 hash is stored)");
    }
  }
}
