package com.vishwesh.repolens.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.ingest.Chunk;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.DocumentSource;
import com.vishwesh.repolens.ingest.MarkdownChunker;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.vishwesh.repolens.ingest.store.DocumentStore;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import com.vishwesh.repolens.support.FakeEmbedder;
import com.vishwesh.repolens.support.TestFakes;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.ai.model.embedding=none",
      "repolens.embedding.warmup=false",
      "repolens.security.api-key-required=true",
      "repolens.security.bootstrap-api-key=test-mcp-key",
      "repolens.rate-limit.enabled=false"
    })
@Import({TestcontainersConfiguration.class, TestFakes.class})
class McpServerIntegrationTests {

  @LocalServerPort private int port;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JdbcClient jdbc;

  private McpSyncClient client;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job, query_log RESTART IDENTITY CASCADE")
        .update();
    long repo = repositories.insert("acme", "demo");
    long documentId =
        documents.upsert(
            repo,
            new DocumentSource(
                DocumentKind.DOC,
                "docs/docker.md",
                "https://github.com/acme/demo/blob/main/docs/docker.md",
                "Docker",
                "docker compose",
                "\"e1\""),
            "h1");
    chunks.insert(
        documentId,
        repo,
        new Chunk(0, "Docker", "docker compose", MarkdownChunker.estimateTokens("docker compose")),
        FakeEmbedder.vectorFor("docker compose"));
    client = client("test-mcp-key");
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void listsRegisteredToolsWithDescriptions() {
    McpSchema.ListToolsResult tools = client.listTools();

    assertThat(tools.tools())
        .extracting(McpSchema.Tool::name)
        .containsExactlyInAnyOrder("list_repositories", "search_docs", "ask_repo", "get_document");
    assertThat(tools.tools()).allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
  }

  @Test
  void listsRepositories() {
    String text = call("list_repositories", Map.of());

    assertThat(text).contains("acme/demo").contains("PENDING");
  }

  @Test
  void searchesDocsWithUntrustedDelimiters() {
    String text =
        call("search_docs", Map.of("repo", "acme/demo", "query", "docker compose", "limit", 3));

    assertThat(text)
        .contains("docs/docker.md")
        .contains(RepoLensMcpTools.UNTRUSTED_BEGIN)
        .contains("docker compose");
  }

  @Test
  void answersQuestionsWithCitations() {
    String text = call("ask_repo", Map.of("repo", "acme/demo", "question", "docker compose"));

    assertThat(text)
        .contains("fake grounded answer")
        .contains("citations")
        .contains("docs/docker.md");
  }

  @Test
  void returnsFullDocumentText() {
    String text = call("get_document", Map.of("repo", "acme/demo", "path", "docs/docker.md"));

    assertThat(text)
        .contains("docker compose")
        .contains(RepoLensMcpTools.UNTRUSTED_BEGIN)
        .contains(RepoLensMcpTools.UNTRUSTED_END);
  }

  @Test
  void reportsUnknownRepositoryAsToolError() {
    McpSchema.CallToolResult result =
        client.callTool(
            new McpSchema.CallToolRequest(
                "search_docs", Map.of("repo", "nope/missing", "query", "x")));

    assertThat(result.isError()).isTrue();
  }

  @Test
  void requiresApiKeyOnHttpTransport() {
    McpSyncClient unauthenticated = client(null);
    try {
      assertThatThrownBy(unauthenticated::initialize).isInstanceOf(RuntimeException.class);
    } finally {
      unauthenticated.close();
    }
  }

  private String call(String tool, Map<String, Object> arguments) {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    return result.content().stream()
        .filter(McpSchema.TextContent.class::isInstance)
        .map(content -> ((McpSchema.TextContent) content).text())
        .collect(Collectors.joining("\n"));
  }

  private McpSyncClient client(String apiKey) {
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
    if (apiKey != null) {
      requestBuilder.header("X-API-Key", apiKey);
    }
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(requestBuilder)
            .build();
    return McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(30))
        .initializationTimeout(Duration.ofSeconds(30))
        .build();
  }
}
