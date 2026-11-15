package com.vishwesh.repolens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "repolens.security")
public record SecurityProperties(
    @DefaultValue("true") boolean apiKeyRequired, @DefaultValue("") String bootstrapApiKey) {}
