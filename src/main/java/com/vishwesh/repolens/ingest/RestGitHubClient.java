package com.vishwesh.repolens.ingest;

import com.vishwesh.repolens.config.RepoLensProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GitHub REST client. No redirects, bounded retries, optional token, ETag support. */
@Component
public class RestGitHubClient implements GitHubClient {

  private static final Logger log = LoggerFactory.getLogger(RestGitHubClient.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final long MAX_BACKOFF_MILLIS = 60_000;

  private final RepoLensProperties properties;
  private final JsonMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;

  @org.springframework.beans.factory.annotation.Autowired
  public RestGitHubClient(RepoLensProperties properties, JsonMapper objectMapper) {
    this(
        properties,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  RestGitHubClient(RepoLensProperties properties, JsonMapper objectMapper, HttpClient httpClient) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.baseUrl =
        properties.github().baseUrl().endsWith("/")
            ? properties.github().baseUrl().substring(0, properties.github().baseUrl().length() - 1)
            : properties.github().baseUrl();
  }

  @Override
  public RepoMetadata fetchRepository(GithubUrl repo) {
    HttpResponse<String> response = get(repoPath(repo), null);
    requireStatus(response, 200, "repository " + repo.slug());
    JsonNode json = parse(response.body());
    String branch = json.path("default_branch").asText(null);
    if (branch == null || branch.isBlank()) {
      throw new GitHubException("Repository " + repo.slug() + " has no default branch");
    }
    return new RepoMetadata(repo.owner(), repo.name(), branch, json.path("html_url").asText());
  }

  @Override
  public List<DocumentRef> listDocuments(GithubUrl repo, String branch) {
    String url = repoPath(repo) + "/git/trees/" + encode(branch) + "?recursive=1";
    HttpResponse<String> response = getAbsolute(url, null);
    requireStatus(response, 200, "tree of " + repo.slug());
    JsonNode json = parse(response.body());
    if (json.path("truncated").asBoolean(false)) {
      log.warn("GitHub tree for {} was truncated; some documents may be missing", repo.slug());
    }
    Map<String, DocumentRef> docs = new LinkedHashMap<>();
    for (JsonNode node : json.path("tree")) {
      if (!"blob".equals(node.path("type").asText())) {
        continue;
      }
      String path = node.path("path").asText();
      long size = node.path("size").asLong(0);
      if (size > properties.github().maxFileBytes()) {
        log.debug("Skipping oversized document {}", path);
        continue;
      }
      DocumentKind kind = classify(path);
      if (kind != null) {
        docs.putIfAbsent(path, new DocumentRef(kind, path));
      }
    }
    return docs.values().stream().sorted(Comparator.comparing(DocumentRef::path)).toList();
  }

  static DocumentKind classify(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    boolean rootReadme = !path.contains("/") && lower.matches("readme(\\.(md|markdown))?");
    if (rootReadme) {
      return DocumentKind.README;
    }
    boolean markdown = lower.endsWith(".md") || lower.endsWith(".markdown");
    if (!markdown) {
      return null;
    }
    boolean topLevel = !path.contains("/");
    boolean underDocs = lower.startsWith("docs/");
    return topLevel || underDocs ? DocumentKind.DOC : null;
  }

  @Override
  public FetchedDocument fetchDocument(
      GithubUrl repo, String branch, DocumentRef ref, String etag) {
    String encodedPath = encodePath(ref.path());
    String url = repoPath(repo) + "/contents/" + encodedPath + "?ref=" + encode(branch);
    HttpResponse<String> response = getAbsolute(url, etag);
    if (response.statusCode() == 304) {
      return FetchedDocument.unchanged();
    }
    if (response.statusCode() == 404) {
      throw new GitHubException("Document not found: " + ref.path());
    }
    requireStatus(response, 200, "document " + ref.path());
    JsonNode json = parse(response.body());
    String content = json.path("content").asText("");
    if (content.isBlank() && json.path("size").asLong(0) > 0) {
      throw new GitHubException(
          "GitHub returned empty content for " + ref.path() + " (file too large)");
    }
    byte[] decoded = Base64.getMimeDecoder().decode(content);
    String text = new String(decoded, StandardCharsets.UTF_8);
    String htmlUrl =
        json.path("html_url").asText(repo.htmlUrl() + "/blob/" + branch + "/" + ref.path());
    String title = json.path("name").asText(ref.path());
    String newEtag = response.headers().firstValue("ETag").orElse(null);
    return FetchedDocument.modified(
        new DocumentSource(ref.kind(), ref.path(), htmlUrl, title, text, newEtag));
  }

  @Override
  public List<DocumentSource> fetchIssues(GithubUrl repo, int limit) {
    List<DocumentSource> issues = new ArrayList<>();
    if (!properties.github().issuesEnabled() || limit <= 0) {
      return issues;
    }
    int perPage = Math.min(100, limit);
    int page = 1;
    while (issues.size() < limit) {
      String url =
          repoPath(repo)
              + "/issues?state=all&per_page="
              + perPage
              + "&page="
              + page
              + "&sort=updated&direction=desc";
      HttpResponse<String> response = getAbsolute(url, null);
      requireStatus(response, 200, "issues of " + repo.slug());
      JsonNode json = parse(response.body());
      if (!json.isArray() || json.size() == 0) {
        break;
      }
      for (JsonNode issue : json) {
        if (!issue.path("pull_request").isMissingNode() && !issue.path("pull_request").isNull()) {
          continue;
        }
        String title = issue.path("title").asText("");
        String body = issue.path("body").asText("");
        String number = issue.path("number").asText();
        issues.add(
            new DocumentSource(
                DocumentKind.ISSUE,
                "issue-" + number,
                issue.path("html_url").asText(),
                title,
                body,
                null));
        if (issues.size() >= limit) {
          break;
        }
      }
      if (json.size() < perPage) {
        break;
      }
      page++;
    }
    return issues;
  }

  private String repoPath(GithubUrl repo) {
    return "/repos/" + encode(repo.owner()) + "/" + encode(repo.name());
  }

  private static String encodePath(String path) {
    String[] parts = path.split("/");
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        builder.append('/');
      }
      builder.append(encode(parts[i]));
    }
    return builder.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private HttpResponse<String> get(String path, String etag) {
    return getAbsolute(path, etag);
  }

  private HttpResponse<String> getAbsolute(String path, String etag) {
    URI uri = URI.create(baseUrl + path);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RepoLens/0.0.1")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET();
    if (!properties.github().token().isBlank()) {
      builder.header("Authorization", "Bearer " + properties.github().token());
    }
    if (etag != null && !etag.isBlank()) {
      builder.header("If-None-Match", etag);
    }
    return sendWithRetries(builder.build(), uri);
  }

  private HttpResponse<String> sendWithRetries(HttpRequest request, URI uri) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        if (attempt + 1 < MAX_ATTEMPTS) {
          sleep(backoffMillis(attempt));
          continue;
        }
        throw new GitHubException("GitHub request failed: " + uri, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new GitHubException("Interrupted while calling GitHub", e);
      }

      int status = response.statusCode();
      if ((status == 403 || status == 429) && attempt + 1 < MAX_ATTEMPTS) {
        long wait = retryDelayMillis(response);
        log.warn("GitHub rate limit hit ({}); waiting {} ms before retry", status, wait);
        sleep(wait);
        continue;
      }
      if (status >= 500 && attempt + 1 < MAX_ATTEMPTS) {
        sleep(backoffMillis(attempt));
        continue;
      }
      return response;
    }
  }

  private static long backoffMillis(int attempt) {
    return Math.min(MAX_BACKOFF_MILLIS, 500L * (1L << attempt));
  }

  private static long retryDelayMillis(HttpResponse<String> response) {
    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
    if (retryAfter != null) {
      try {
        return Math.min(MAX_BACKOFF_MILLIS, Long.parseLong(retryAfter.trim()) * 1000L);
      } catch (NumberFormatException ignored) {
        // fall through to the reset header
      }
    }
    String reset = response.headers().firstValue("X-RateLimit-Reset").orElse(null);
    if (reset != null) {
      try {
        long epochSeconds = Long.parseLong(reset.trim());
        long wait = epochSeconds - Instant.now().getEpochSecond() + 1;
        return Math.max(1000, Math.min(MAX_BACKOFF_MILLIS, wait * 1000L));
      } catch (NumberFormatException ignored) {
        // fall through to the default
      }
    }
    return 1000;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrupted during GitHub backoff", e);
    }
  }

  private static void requireStatus(
      HttpResponse<String> response, int expected, String description) {
    if (response.statusCode() != expected) {
      throw new GitHubException(
          "GitHub request for "
              + description
              + " returned HTTP "
              + response.statusCode()
              + ": "
              + truncate(response.body()));
    }
  }

  private JsonNode parse(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JacksonException e) {
      throw new GitHubException("Invalid JSON from GitHub: " + truncate(body), e);
    }
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 300 ? body : body.substring(0, 300) + "...";
  }
}
