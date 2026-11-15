package com.vishwesh.repolens.answer;

import com.vishwesh.repolens.config.RepoLensProperties;
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
@ConditionalOnExpression("'${repolens.llm.provider:fake}' != 'fake'")
public class OpenAiCompatibleLlmGateway implements LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmGateway.class);

  private final RepoLensProperties.Llm config;
  private final JsonMapper objectMapper;
  private final HttpClient httpClient;
  private final URI completionsUri;

  public OpenAiCompatibleLlmGateway(RepoLensProperties properties, JsonMapper objectMapper) {
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
          "repolens.llm.model / LLM_MODEL must be set for provider " + providerName());
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
    HttpResponse<T> response;
    try {
      response = httpClient.send(builder.build(), handler);
    } catch (IOException e) {
      throw new LlmException("LLM request failed: " + completionsUri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while calling the LLM", e);
    }
    if (response.statusCode() != 200) {
      throw new LlmException(
          "LLM provider returned HTTP " + response.statusCode() + " for " + completionsUri);
    }
    return response;
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

  private JsonNode parse(String body, String what) {
    try {
      return objectMapper.readTree(body);
    } catch (RuntimeException e) {
      String truncated = body == null || body.length() <= 300 ? body : body.substring(0, 300);
      throw new LlmException("Invalid JSON in " + what + ": " + truncated, e);
    }
  }
}
