package com.vishwesh.repoprism.ratelimit;

import com.vishwesh.repoprism.observability.RepoPrismMetrics;
import com.vishwesh.repoprism.security.ApiKeyFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Per-key (fallback IP) token-bucket limiting with standard {@code RateLimit-*} headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitService rateLimitService;
  private final JsonMapper objectMapper;
  private final RepoPrismMetrics metrics;

  public RateLimitFilter(
      RateLimitService rateLimitService, JsonMapper objectMapper, RepoPrismMetrics metrics) {
    this.rateLimitService = rateLimitService;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!rateLimitService.enabled()) {
      return true;
    }
    String path = request.getRequestURI();
    return !(path.startsWith("/api/") || path.startsWith("/mcp"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    RateLimitScope scope = scope(request.getRequestURI());
    String identity = identity(request);
    TokenBucket bucket = rateLimitService.bucket(identity, scope);

    boolean allowed = bucket.tryAcquire();
    long remaining = bucket.remaining();
    long retryAfter = bucket.retryAfterSeconds();

    response.setHeader("RateLimit-Limit", Long.toString(bucket.capacity()));
    response.setHeader("RateLimit-Remaining", Long.toString(remaining));
    response.setHeader("RateLimit-Reset", Long.toString(retryAfter));
    response.setHeader("RateLimit-Scope", scope.name());

    if (!allowed) {
      metrics.rateLimitRejected(scope.name());
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfter)));
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      Map<String, Object> problem = new LinkedHashMap<>();
      problem.put("type", "about:blank");
      problem.put("title", "Too Many Requests");
      problem.put("status", 429);
      problem.put("detail", "Rate limit exceeded for scope " + scope);
      problem.put("instance", request.getRequestURI());
      objectMapper.writeValue(response.getWriter(), problem);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static RateLimitScope scope(String path) {
    if (path.endsWith("/ask") || path.contains("/ask/stream")) {
      return RateLimitScope.ASK;
    }
    if (path.endsWith("/search")) {
      return RateLimitScope.SEARCH;
    }
    return RateLimitScope.GENERAL;
  }

  private static String identity(HttpServletRequest request) {
    Object keyId = request.getAttribute(ApiKeyFilter.API_KEY_ID_ATTRIBUTE);
    if (keyId != null) {
      return "key:" + keyId;
    }
    return "ip:" + request.getRemoteAddr();
  }
}
