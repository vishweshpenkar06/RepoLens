package com.vishwesh.repoprism.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "repoprism.security")
public record SecurityProperties(
    @DefaultValue("true") boolean apiKeyRequired,
    @DefaultValue("") String bootstrapApiKey,
    @DefaultValue("60s") Duration apiKeyCacheTtl) {}
