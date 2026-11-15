package com.vishwesh.repolens.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propagates a validated request id through MDC and the response header. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    String requestId =
        provided != null && SAFE.matcher(provided).matches()
            ? provided
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, requestId);
    response.setHeader(HEADER, requestId);
    long started = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (!request.getRequestURI().startsWith("/actuator")) {
        log.info(
            "{} {} -> {} ({} ms)",
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      }
      MDC.remove(MDC_KEY);
    }
  }
}
