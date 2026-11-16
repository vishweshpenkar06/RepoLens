package com.vishwesh.repolens.answer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.vishwesh.repolens.config.RepoLensProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleLlmGatewayTests {

  private WireMockServer server;
  private OpenAiCompatibleLlmGateway gateway;

  @BeforeEach
  void setUp() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
    gateway = gateway(server.baseUrl(), "test-model", "secret", true);
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void postsChatCompletionAndParsesContent() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer secret"))
            .willReturn(
                ok(
                    """
                    {"choices":[{"message":{"content":"{\\"answer\\":\\"A [1]\\",\\"citations\\":[1],\\"refused\\":false}"}}]}
                    """)));

    String content = gateway.complete(new LlmRequest("system", "user"));

    assertThat(content).contains("\"answer\"");
    server.verify(
        com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("/v1/chat/completions"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.model", equalTo("test-model")))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.response_format.type", equalTo("json_object"))));
  }

  @Test
  void streamsSseTokens() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(
                        """
                        data: {"choices":[{"delta":{"content":"Hello "}}]}

                        data: {"choices":[{"delta":{"content":"world [1]"}}]}

                        data: [DONE]

                        """)));

    List<String> tokens = new ArrayList<>();
    gateway.stream(new LlmRequest("system", "user"), tokens::add);

    assertThat(tokens).containsExactly("Hello ", "world [1]");
  }

  @Test
  void failsWithoutConfiguredModel() {
    OpenAiCompatibleLlmGateway unconfigured = gateway(server.baseUrl(), "", "secret", true);
    assertThatThrownBy(() -> unconfigured.complete(new LlmRequest("s", "u")))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("LLM_MODEL");
  }

  @Test
  void retriesAfterRateLimit() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .inScenario("rate-limit")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("ok"));
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .inScenario("rate-limit")
            .whenScenarioStateIs("ok")
            .willReturn(ok("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")));

    assertThat(gateway.complete(new LlmRequest("s", "u"))).isEqualTo("ok");
  }

  @Test
  void retriesOnJsonValidationFailure() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .inScenario("json-retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":{\"code\":\"json_validate_failed\","
                            + "\"message\":\"Failed to validate JSON\"}}"))
            .willSetStateTo("ok"));
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .inScenario("json-retry")
            .whenScenarioStateIs("ok")
            .willReturn(ok("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")));

    assertThat(gateway.complete(new LlmRequest("s", "u"))).isEqualTo("ok");
  }

  @Test
  void includesProviderBodyInErrors() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(401).withBody("bad key")));

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("s", "u")))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("401")
        .hasMessageContaining("bad key");
  }

  @Test
  void parsesGroqRateLimitDurations() {
    assertThat(OpenAiCompatibleLlmGateway.parseDurationMillis("585ms")).isEqualTo(585);
    assertThat(OpenAiCompatibleLlmGateway.parseDurationMillis("1.5s")).isEqualTo(1500);
    assertThat(OpenAiCompatibleLlmGateway.parseDurationMillis("1m26.4s")).isEqualTo(86_400);
    assertThat(OpenAiCompatibleLlmGateway.parseDurationMillis("nonsense")).isZero();
  }

  @Test
  void failsOnNonSuccessStatus() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(() -> gateway.complete(new LlmRequest("s", "u")))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("500");
  }

  @Test
  void omitsJsonModeWhenDisabled() {
    OpenAiCompatibleLlmGateway plain = gateway(server.baseUrl(), "test-model", "", false);
    server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(ok("{\"choices\":[]}")));

    assertThatThrownBy(() -> plain.complete(new LlmRequest("s", "u")))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("empty");

    String requestBody = server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
    assertThat(requestBody).doesNotContain("response_format");
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ok(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static OpenAiCompatibleLlmGateway gateway(
      String baseUrl, String model, String apiKey, boolean jsonMode) {
    RepoLensProperties properties =
        new RepoLensProperties(
            new RepoLensProperties.Github("", "https://api.github.com", true, 300, 1_048_576),
            new RepoLensProperties.Embedding(2, 32, 384, false),
            new RepoLensProperties.Retrieval(30, 6, 60),
            new RepoLensProperties.Ask(Duration.ofSeconds(20), 1000, 12),
            new RepoLensProperties.Llm(
                "openai",
                baseUrl + "/v1",
                apiKey,
                model,
                Duration.ofSeconds(5),
                100,
                0.0,
                jsonMode,
                Duration.ZERO),
            new RepoLensProperties.Cache(Duration.ofHours(1), 100, Duration.ofHours(1), 100));
    return new OpenAiCompatibleLlmGateway(properties, JsonMapper.builder().build());
  }
}
