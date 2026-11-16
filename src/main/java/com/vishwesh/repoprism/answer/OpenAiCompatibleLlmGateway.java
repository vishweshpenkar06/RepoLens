package com.vishwesh.repoprism.answer;

import com.vishwesh.repoprism.config.RepoPrismProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM gateway for any OpenAI-compatible chat-completions endpoint: OpenAI, OpenRouter, Groq,
 * Together, vLLM, LM Studio, and Ollama (`/v1`).
 */
@Component
@ConditionalOnExpression("'${repoprism.llm.provider:fake}' != 'fake'")
public class OpenAiCompatibleLlmGateway implements LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmGateway.class);
  private static final int MAX_ATTEMPTS = 4;
  private static final int MAX_THROTTLE_ATTEMPTS = 6;
  private static final long MAX_BACKOFF_MILLIS = 60_000;
  private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m)");

  private final RepoPrismProperties.Llm config;
  private final JsonMapper objectMapper;
  private final HttpClient httpClient;
  private final URI completionsUri;

  public OpenAiCompatibleLlmGateway(RepoPrismProperties properties, JsonMapper objectMapper) {
    this.config = properties.llm();
    this.objectMapper = objectMapper;
    String baseUrl =
        config.baseUrl().endsWith("/")
            ? config.baseUrl().substring(0, config.baseUrl().length() - 1)
            : config.baseUrl();
    this.completionsUri = URI.create(baseUrl + "/chat/completions");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public String complete(LlmRequest request) {
    HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(), false);
    JsonNode json = parse(response.body(), "completion response");
    String content = json.path("choices").path(0).path("message").path("content").asText(null);
    if (content == null || content.isBlank()) {
      throw new LlmException("LLM returned an empty completion");
    }
    return content;
  }

  @Override
  public void stream(LlmRequest request, Consumer<String> onToken) {
    HttpResponse<Stream<String>> response =
        send(request, HttpResponse.BodyHandlers.ofLines(), true);
    try (Stream<String> lines = response.body()) {
      lines.forEach(line -> {
        String trimmed = line.strip();
        if (!trimmed.startsWith("data:")) {
          return;
        }
        String payload = trimmed.substring(5).strip();
        if (payload.equals("[DONE]")) {
          return;
        }
        try {
          JsonNode chunk = objectMapper.readTree(payload);
          JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
          if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
            onToken.accept(delta.asText());
          }
        } catch (RuntimeException e) {
          log.debug("Ignoring unparseable SSE chunk: {}", payload);
        }
      });
    }
  }

  @Override
  public String providerName() {
    return config.provider();
  }

  private <T> HttpResponse<T> send(
      LlmRequest request, HttpResponse.BodyHandler<T> handler, boolean streaming) {
    if (config.model() == null || config.model().isBlank()) {
      throw new LlmException(
          "repoprism.llm.model / LLM_MODEL must be set for provider " + providerName());
    }
    String body = requestBody(request, streaming);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(completionsUri)
            .timeout(config.timeout())
            .header("Content-Type", "application/json")
            .header("Accept", streaming ? "text/event-stream" : "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (config.apiKey() != null && !config.apiKey().isBlank()) {
      builder.header("Authorization", "Bearer " + config.apiKey());
    }
    HttpRequest httpRequest = builder.build();
    for (int attempt = 0; ; attempt++) {
      HttpResponse<T> response;
      try {
        response = httpClient.send(httpRequest, handler);
      } catch (IOException e) {
        if (attempt + 1 < MAX_ATTEMPTS) {
          sleep(backoffMillis(attempt));
          continue;
        }
        throw new LlmException("LLM request failed: " + completionsUri, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmException("Interrupted while calling the LLM", e);
      }
      int status = response.statusCode();
      boolean jsonValidationFailure =
          status == 400
              && response.body() instanceof String responseBody
              && isJsonValidationFailure(responseBody);
      int maxAttempts = status == 429 ? MAX_THROTTLE_ATTEMPTS : MAX_ATTEMPTS;
      if ((status == 429 || status >= 500 || jsonValidationFailure) && attempt + 1 < maxAttempts) {
        long wait = status == 429 ? retryDelayMillis(response, attempt) : backoffMillis(attempt);
        log.warn("LLM provider returned {}; retrying in {} ms", status, wait);
        sleep(wait);
        continue;
      }
      if (status != 200) {
        throw new LlmException(
            "LLM provider returned HTTP "
                + status
                + " for "
                + completionsUri
                + ": "
                + truncate(response.body()));
      }
      return response;
    }
  }

  private String requestBody(LlmRequest request, boolean streaming) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", config.model());
    body.put(
        "messages",
        List.of(
            Map.of("role", "system", "content", request.systemPrompt()),
            Map.of("role", "user", "content", request.userPrompt())));
    body.put("temperature", config.temperature());
    body.put("max_tokens", config.maxTokens());
    if (streaming) {
      body.put("stream", true);
    } else if (config.jsonMode()) {
      body.put("response_format", Map.of("type", "json_object"));
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      throw new LlmException("Cannot serialize LLM request", e);
    }
  }

  private static boolean isJsonValidationFailure(String body) {
    return body.contains("Failed to validate JSON")
        || body.contains("json_validate_failed")
        || body.contains("failed_generation");
  }

  private static String truncate(Object body) {
    String text = body == null ? "" : body.toString();
    return text.length() <= 300 ? text : text.substring(0, 300);
  }

  private static long backoffMillis(int attempt) {
    return Math.min(MAX_BACKOFF_MILLIS, 1000L * (1L << attempt));
  }

  private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
    String body = response.body() == null ? "" : response.body().toString();
    Matcher googleRetry = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").matcher(body);
    if (googleRetry.find()) {
      return Math.min(MAX_BACKOFF_MILLIS, (long) (Double.parseDouble(googleRetry.group(1)) * 1000));
    }
    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
    if (retryAfter != null) {
      try {
        return Math.min(MAX_BACKOFF_MILLIS, Long.parseLong(retryAfter.trim()) * 1000L);
      } catch (NumberFormatException ignored) {
        // fall through to token reset headers
      }
    }
    String reset =
        response
            .headers()
            .firstValue("X-RateLimit-Reset-Tokens")
            .or(() -> response.headers().firstValue("X-RateLimit-Reset-Requests"))
            .orElse(null);
    if (reset != null) {
      long parsed = parseDurationMillis(reset);
      if (parsed > 0) {
        return Math.min(MAX_BACKOFF_MILLIS, parsed);
      }
    }
    return Math.max(5_000, backoffMillis(attempt + 2));
  }

  static long parseDurationMillis(String value) {
    Matcher matcher = DURATION_PART.matcher(value);
    long total = 0;
    boolean found = false;
    while (matcher.find()) {
      found = true;
      double amount = Double.parseDouble(matcher.group(1));
      total +=
          switch (matcher.group(2)) {
            case "ms" -> (long) amount;
            case "m" -> (long) (amount * 60_000);
            default -> (long) (amount * 1000);
          };
    }
    return found ? total : 0;
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted during LLM backoff", e);
    }
  }

  private JsonNode parse(String body, String what) {
    try {
      return objectMapper.readTree(body);
    } catch (RuntimeException e) {
      String truncated = body == null || body.length() <= 300 ? body : body.substring(0, 300);
      throw new LlmException("Invalid JSON in " + what + ": " + truncated, e);
    }
  }
}
