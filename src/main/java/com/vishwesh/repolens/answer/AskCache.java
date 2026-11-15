package com.vishwesh.repolens.answer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vishwesh.repolens.config.RepoLensProperties;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * In-process answer cache. The key contains the repository content version, so re-ingestion
 * invalidates answers without an explicit eviction step.
 */
@Component
public class AskCache {

  private final Cache<String, CachedAnswer> cache;

  public AskCache(RepoLensProperties properties) {
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.cache().answerTtl())
            .maximumSize(properties.cache().answerMaxSize())
            .build();
  }

  public Optional<CachedAnswer> get(String key) {
    return Optional.ofNullable(cache.getIfPresent(key));
  }

  public void put(String key, CachedAnswer answer) {
    cache.put(key, answer);
  }

  public void invalidateAll() {
    cache.invalidateAll();
  }

  public static String key(long repositoryId, String contentVersion, String question, int topK) {
    return repositoryId
        + "|"
        + (contentVersion == null ? "none" : contentVersion)
        + "|"
        + normalize(question)
        + "|"
        + topK;
  }

  static String normalize(String question) {
    return question == null
        ? ""
        : question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
