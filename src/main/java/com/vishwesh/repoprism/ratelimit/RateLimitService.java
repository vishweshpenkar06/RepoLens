package com.vishwesh.repoprism.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vishwesh.repoprism.config.RateLimitProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Per-identity, per-scope token buckets with idle eviction. */
@Component
public class RateLimitService {

  private final RateLimitProperties properties;
  private final Cache<String, TokenBucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(100_000).build();

  public RateLimitService(RateLimitProperties properties) {
    this.properties = properties;
  }

  public TokenBucket bucket(String identity, RateLimitScope scope) {
    String key = identity + "|" + scope.name();
    return buckets.get(
        key, ignored -> new TokenBucket(capacity(scope), refillPerSecond(scope), System::nanoTime));
  }

  public boolean enabled() {
    return properties.enabled();
  }

  private long capacity(RateLimitScope scope) {
    return switch (scope) {
      case ASK -> properties.askCapacity();
      case SEARCH -> properties.searchCapacity();
      case GENERAL -> properties.generalCapacity();
    };
  }

  private double refillPerSecond(RateLimitScope scope) {
    return switch (scope) {
      case ASK -> properties.askRefillPerSecond();
      case SEARCH -> properties.searchRefillPerSecond();
      case GENERAL -> properties.generalRefillPerSecond();
    };
  }
}
