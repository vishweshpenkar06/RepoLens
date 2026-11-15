package com.vishwesh.repolens.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.vishwesh.repolens.config.RepoLensProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RestGitHubClientTests {

  private WireMockServer server;
  private RestGitHubClient client;
  private final GithubUrl repo = new GithubUrl("acme", "demo");

  @BeforeEach
  void setUp() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
    RepoLensProperties properties =
        new RepoLensProperties(
            new RepoLensProperties.Github("", server.baseUrl(), true, 300, 1024 * 1024),
            new RepoLensProperties.Embedding(2, 32, 384, false),
            new RepoLensProperties.Retrieval(30, 6, 60),
            new RepoLensProperties.Ask(Duration.ofSeconds(20), 1000, 12),
            RepoLensProperties.defaultLlm(),
            RepoLensProperties.defaultCache());
    client = new RestGitHubClient(properties, JsonMapper.builder().build());
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void fetchesRepositoryMetadata() {
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo"))
            .willReturn(
                okJson(
                    """
                    {"default_branch":"main","html_url":"https://github.com/acme/demo"}
                    """)));

    RepoMetadata metadata = client.fetchRepository(repo);

    assertThat(metadata.defaultBranch()).isEqualTo("main");
    assertThat(metadata.htmlUrl()).isEqualTo("https://github.com/acme/demo");
  }

  @Test
  void failsWhenDefaultBranchMissing() {
    server.stubFor(get(urlEqualTo("/repos/acme/demo")).willReturn(okJson("{}")));
    assertThatThrownBy(() -> client.fetchRepository(repo)).isInstanceOf(GitHubException.class);
  }

  @Test
  void listsOnlyDocumentationMarkdown() {
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo/git/trees/main?recursive=1"))
            .willReturn(
                okJson(
                    """
                    {
                      "truncated": false,
                      "tree": [
                        {"path":"README.md","type":"blob","size":10},
                        {"path":"CONTRIBUTING.md","type":"blob","size":10},
                        {"path":"docs/setup.md","type":"blob","size":10},
                        {"path":"docs/guides/deep.markdown","type":"blob","size":10},
                        {"path":"docs/logo.png","type":"blob","size":10},
                        {"path":"src/Main.java","type":"blob","size":10},
                        {"path":"docs","type":"tree"},
                        {"path":"docs/huge.md","type":"blob","size":2000000}
                      ]
                    }
                    """)));

    List<DocumentRef> refs = client.listDocuments(repo, "main");

    assertThat(refs)
        .extracting(DocumentRef::path)
        .containsExactly(
            "CONTRIBUTING.md", "README.md", "docs/guides/deep.markdown", "docs/setup.md");
    assertThat(refs.stream().filter(ref -> ref.kind() == DocumentKind.README))
        .extracting(DocumentRef::path)
        .containsExactly("README.md");
  }

  @Test
  void classifiesPaths() {
    assertThat(RestGitHubClient.classify("README.md")).isEqualTo(DocumentKind.README);
    assertThat(RestGitHubClient.classify("README.markdown")).isEqualTo(DocumentKind.README);
    assertThat(RestGitHubClient.classify("README")).isEqualTo(DocumentKind.README);
    assertThat(RestGitHubClient.classify("docs/README")).isNull();
    assertThat(RestGitHubClient.classify("README.rst")).isNull();
    assertThat(RestGitHubClient.classify("CONTRIBUTING.md")).isEqualTo(DocumentKind.DOC);
    assertThat(RestGitHubClient.classify("docs/setup.md")).isEqualTo(DocumentKind.DOC);
    assertThat(RestGitHubClient.classify("docs/a/b.md")).isEqualTo(DocumentKind.DOC);
    assertThat(RestGitHubClient.classify("src/Main.java")).isNull();
    assertThat(RestGitHubClient.classify("other/nested.md")).isNull();
  }

  @Test
  void decodesDocumentAndEtag() {
    String content = "# Demo\n\nHello";
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo/contents/README.md?ref=main"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("ETag", "\"etag-1\"")
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"name":"README.md","path":"README.md","html_url":"https://github.com/acme/demo/blob/main/README.md","size":%d,"encoding":"base64","content":"%s"}
                        """
                            .formatted(
                                content.length(),
                                Base64.getEncoder()
                                    .encodeToString(content.getBytes(StandardCharsets.UTF_8))))));

    FetchedDocument fetched =
        client.fetchDocument(repo, "main", new DocumentRef(DocumentKind.README, "README.md"), null);

    assertThat(fetched.notModified()).isFalse();
    assertThat(fetched.document().content()).isEqualTo(content);
    assertThat(fetched.document().etag()).isEqualTo("\"etag-1\"");
    assertThat(fetched.document().url()).contains("/blob/main/README.md");
  }

  @Test
  void reportsUnchangedDocumentOn304() {
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo/contents/README.md?ref=main"))
            .withHeader("If-None-Match", equalTo("\"etag-1\""))
            .willReturn(aResponse().withStatus(304)));

    FetchedDocument fetched =
        client.fetchDocument(
            repo, "main", new DocumentRef(DocumentKind.README, "README.md"), "\"etag-1\"");

    assertThat(fetched.notModified()).isTrue();
    assertThat(fetched.document()).isNull();
  }

  @Test
  void failsOnMissingDocument() {
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo/contents/README.md?ref=main"))
            .willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() ->
            client.fetchDocument(
                repo, "main", new DocumentRef(DocumentKind.README, "README.md"), null))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void filtersPullRequestsAndRespectsLimit() {
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/demo/issues"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(
                okJson(
                    """
                    [
                      {"number":1,"title":"Bug","body":"Body","html_url":"https://github.com/acme/demo/issues/1"},
                      {"number":2,"title":"PR","body":"x","html_url":"u","pull_request":{"url":"p"}},
                      {"number":3,"title":"Feature","body":null,"html_url":"https://github.com/acme/demo/issues/3"}
                    ]
                    """)));

    List<DocumentSource> issues = client.fetchIssues(repo, 2);

    assertThat(issues).hasSize(2);
    assertThat(issues).extracting(DocumentSource::pathOrRef).containsExactly("issue-1", "issue-3");
    assertThat(issues.get(1).content()).isEmpty();
    assertThat(issues.getFirst().kind()).isEqualTo(DocumentKind.ISSUE);
  }

  @Test
  void returnsNoIssuesWhenDisabled() {
    RestGitHubClient disabled =
        new RestGitHubClient(
            new RepoLensProperties(
                new RepoLensProperties.Github("", server.baseUrl(), false, 300, 1024 * 1024),
                new RepoLensProperties.Embedding(2, 32, 384, false),
                new RepoLensProperties.Retrieval(30, 6, 60),
                new RepoLensProperties.Ask(Duration.ofSeconds(20), 1000, 12),
                RepoLensProperties.defaultLlm(),
                RepoLensProperties.defaultCache()),
            JsonMapper.builder().build());

    assertThat(disabled.fetchIssues(repo, 10)).isEmpty();
  }

  @Test
  void retriesAfterRateLimit() {
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo"))
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(403).withHeader("Retry-After", "0"))
            .willSetStateTo("ok"));
    server.stubFor(
        get(urlEqualTo("/repos/acme/demo"))
            .inScenario("retry")
            .whenScenarioStateIs("ok")
            .willReturn(okJson("{\"default_branch\":\"main\"}")));

    assertThat(client.fetchRepository(repo).defaultBranch()).isEqualTo("main");
  }
}
