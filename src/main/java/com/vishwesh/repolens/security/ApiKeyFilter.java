package com.vishwesh.repolens.security;

import com.vishwesh.repolens.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Requires a valid, non-revoked {@code X-API-Key} for REST and HTTP MCP endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-API-Key";
  public static final String API_KEY_ID_ATTRIBUTE = "repolens.apiKeyId";

  private final ApiKeyService apiKeyService;
  private final SecurityProperties properties;
  private final JsonMapper objectMapper;

  public ApiKeyFilter(
      ApiKeyService apiKeyService, SecurityProperties properties, JsonMapper objectMapper) {
    this.apiKeyService = apiKeyService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!properties.apiKeyRequired()) {
      return true;
    }
    String path = request.getRequestURI();
    if (path.startsWith("/actuator") || path.equals("/error")) {
      return true;
    }
    return !(path.startsWith("/api/") || path.startsWith("/mcp"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<Long> keyId = apiKeyService.authenticate(request.getHeader(HEADER));
    if (keyId.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      Map<String, Object> problem = new LinkedHashMap<>();
      problem.put("type", "about:blank");
      problem.put("title", "Unauthorized");
      problem.put("status", 401);
      problem.put("detail", "A valid X-API-Key header is required");
      problem.put("instance", request.getRequestURI());
      response.getWriter().write(objectMapper.writeValueAsString(problem));
      return;
    }
    request.setAttribute(API_KEY_ID_ATTRIBUTE, keyId.get());
    filterChain.doFilter(request, response);
  }
}
