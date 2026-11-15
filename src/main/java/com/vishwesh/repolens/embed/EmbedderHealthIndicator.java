package com.vishwesh.repolens.embed;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("embedder")
public class EmbedderHealthIndicator implements HealthIndicator {

  private final Embedder embedder;

  public EmbedderHealthIndicator(Embedder embedder) {
    this.embedder = embedder;
  }

  @Override
  public Health health() {
    return embedder.ready()
        ? Health.up().withDetail("dimensions", embedder.dimensions()).build()
        : Health.down().withDetail("reason", "embedding model not ready").build();
  }
}
