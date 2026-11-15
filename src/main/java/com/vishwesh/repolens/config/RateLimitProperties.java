package com.vishwesh.repolens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "repolens.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10") long askCapacity,
    @DefaultValue("0.5") double askRefillPerSecond,
    @DefaultValue("30") long searchCapacity,
    @DefaultValue("5") double searchRefillPerSecond,
    @DefaultValue("60") long generalCapacity,
    @DefaultValue("10") double generalRefillPerSecond) {}
