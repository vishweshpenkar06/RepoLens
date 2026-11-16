package com.vishwesh.repolens.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vishwesh.repolens.common.Hashing;
import com.vishwesh.repolens.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * API keys are stored as SHA-256 hashes and are revocable. Lookup is by hash (unique index); the
 * secret itself is never stored or logged.
 */
@Service
public class ApiKeyService {

  private static final int MAX_KEY_LENGTH = 512;

  private final JdbcClient jdbc;
  private final SecurityProperties properties;
  private final Cache<String, Optional<Long>> cache;

  public ApiKeyService(JdbcClient jdbc, SecurityProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.apiKeyCacheTtl())
            .maximumSize(10_000)
            .build();
  }

  public Optional<Long> authenticate(String rawKey) {
    if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_LENGTH) {
      return Optional.empty();
    }
    String presented = hash(rawKey.strip());
    boolean caching = !properties.apiKeyCacheTtl().isZero();
    if (caching) {
      Optional<Long> cached = cache.getIfPresent(presented);
      if (cached != null) {
        return cached;
      }
    }
    Optional<Long> authenticated = lookup(presented);
    if (caching) {
      cache.put(presented, authenticated);
    }
    return authenticated;
  }

  private Optional<Long> lookup(String presented) {
    List<KeyHash> matches =
        jdbc.sql("SELECT id, key_hash FROM api_key WHERE key_hash = ? AND revoked = false")
            .param(presented)
            .query((rs, rowNum) -> new KeyHash(rs.getLong("id"), rs.getString("key_hash")))
            .list();
    return matches.stream()
        .filter(match ->
            MessageDigest.isEqual(
                match.hash().getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8)))
        .map(KeyHash::id)
        .findFirst();
  }

  public void bootstrap(String rawKey, String label) {
    if (rawKey == null || rawKey.isBlank()) {
      return;
    }
    jdbc.sql(
            """
            INSERT INTO api_key (key_hash, label)
            VALUES (?, ?)
            ON CONFLICT (key_hash) DO NOTHING
            """)
        .params(hash(rawKey.strip()), label)
        .update();
    cache.invalidate(hash(rawKey.strip()));
  }

  public static String hash(String rawKey) {
    return Hashing.sha256(rawKey);
  }

  private record KeyHash(long id, String hash) {}
}
