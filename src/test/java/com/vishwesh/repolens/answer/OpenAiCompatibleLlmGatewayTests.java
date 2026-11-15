package com.vishwesh.repolens.answer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
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
