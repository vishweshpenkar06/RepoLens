package com.vishwesh.repoprism.mcp;

import com.vishwesh.repoprism.answer.AnswerResult;
import com.vishwesh.repoprism.answer.AskService;
import com.vishwesh.repoprism.answer.Citation;
import com.vishwesh.repoprism.ingest.DocumentRow;
import com.vishwesh.repoprism.ingest.RepositoryRow;
import com.vishwesh.repoprism.ingest.store.DocumentStore;
import com.vishwesh.repoprism.ingest.store.RepositoryStore;
import com.vishwesh.repoprism.retrieve.SearchResult;
import com.vishwesh.repoprism.retrieve.SearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposed to AI assistants. Descriptions are written for an LLM reader. Retrieved text is
 * always wrapped in untrusted-document delimiters so it cannot be mistaken for instructions.
 */
@Component
public class RepoPrismMcpTools {

  static final String UNTRUSTED_BEGIN = "<<<UNTRUSTED_DOCUMENT>>>";
  static final String UNTRUSTED_END = "<<<END_UNTRUSTED_DOCUMENT>>>";
  private static final int MAX_DOCUMENT_CHARS = 20_000;

  private final RepositoryStore repositories;
  private final DocumentStore documents;
  private final SearchService searchService;
  private final AskService askService;

  public RepoPrismMcpTools(
      RepositoryStore repositories,
      DocumentStore documents,
      SearchService searchService,
      AskService askService) {
    this.repositories = repositories;
    this.documents = documents;
    this.searchService = searchService;
    this.askService = askService;
  }

  @McpTool(
      name = "list_repositories",
      description =
          """
          List every repository registered in RepoPrism with its id, slug and ingestion status.
          Use this first when you do not know the exact repository name. Input: none.
          Example: call with {} and pick repos[0].slug for later calls.
          """)
  public List<Map<String, Object>> listRepositories() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (RepositoryRow repository : repositories.findAll()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", repository.id());
      entry.put("repo", repository.owner() + "/" + repository.name());
      entry.put("status", repository.status());
      entry.put("defaultBranch", repository.defaultBranch());
      entry.put("lastIngestedAt", repository.lastIngestedAt());
      result.add(entry);
    }
    return result;
  }

  @McpTool(
      name = "search_docs",
      description =
          """
          Hybrid search (vector + full-text, fused with Reciprocal Rank Fusion) over one repository's
          documentation and issues. Returns ranked snippets with source URLs and ranks. Use when you
          need raw passages or links rather than a synthesized answer.
          Inputs: repo = "owner/name" (see list_repositories), query = natural language or keywords,
          limit = 1..12 (optional, default 6).
          Example: {"repo":"octocat/Hello-World","query":"how to fork","limit":5}
          """)
  public List<Map<String, Object>> searchDocs(
      @McpToolParam(
              description = "Repository as owner/name, from list_repositories",
              required = true)
          String repo,
      @McpToolParam(description = "Natural-language or keyword query", required = true)
          String query,
      @McpToolParam(description = "Maximum results, 1..12 (optional, default 6)", required = false)
          Integer limit) {
    RepositoryRow repository = repository(repo);
    int clamped = limit == null ? 6 : Math.max(1, Math.min(12, limit));
    List<SearchResult> results = searchService.search(repository.id(), query, clamped);
    List<Map<String, Object>> output = new ArrayList<>(results.size());
    for (SearchResult result : results) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("title", result.title());
      entry.put("url", result.url());
      entry.put("path", result.pathOrRef());
      entry.put("headingPath", result.headingPath());
      entry.put("snippet", untrusted(result.snippet()));
      entry.put("score", result.score());
      entry.put("vectorRank", result.vectorRank());
      entry.put("keywordRank", result.keywordRank());
      output.add(entry);
    }
    return output;
  }

  @McpTool(
      name = "ask_repo",
      description =
          """
          Ask a grounded question about one repository. The answer is generated only from retrieved
          documentation/issues and cites sources with [n] markers; if evidence is missing the result
          is refused. Prefer this over search_docs when the user wants an answer, not passages.
          Inputs: repo = "owner/name", question = user question (max 1000 chars).
          Example: {"repo":"octocat/Hello-World","question":"What is this repository?"}
          """)
  public Map<String, Object> askRepo(
      @McpToolParam(description = "Repository as owner/name", required = true) String repo,
      @McpToolParam(
              description = "Question to answer from repository documentation",
              required = true)
          String question) {
    RepositoryRow repository = repository(repo);
    AnswerResult result = askService.ask(repository.id(), question, null);
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("answer", result.answer());
    output.put("refused", result.refused());
    output.put("cached", result.cached());
    output.put("latencyMs", result.latencyMs());
    List<Map<String, Object>> citations = new ArrayList<>(result.citations().size());
    for (Citation citation : result.citations()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("index", citation.index());
      entry.put("title", citation.title());
      entry.put("url", citation.url());
      entry.put("headingPath", citation.headingPath());
      entry.put("snippet", untrusted(citation.snippet()));
      citations.add(entry);
    }
    output.put("citations", citations);
    return output;
  }

  @McpTool(
      name = "get_document",
      description =
          """
          Return the full text of one document (README, docs page or issue) from a repository,
          truncated to 20,000 characters. Use after search_docs when the snippet is not enough.
          Inputs: repo = "owner/name", path = path_or_ref from search results (for example
          "docs/setup.md" or "issue-42").
          Example: {"repo":"octocat/Hello-World","path":"README"}
          """)
  public Map<String, Object> getDocument(
      @McpToolParam(description = "Repository as owner/name", required = true) String repo,
      @McpToolParam(description = "Document path or issue reference (path_or_ref)", required = true)
          String path) {
    RepositoryRow repository = repository(repo);
    Optional<DocumentRow> document =
        documents.findByRepositoryAndPathOrRef(repository.id(), path.strip());
    if (document.isEmpty()) {
      throw new IllegalArgumentException(
          "Document " + path + " not found in " + repository.owner() + "/" + repository.name());
    }
    DocumentRow row = document.get();
    String content = row.content();
    boolean truncated = content != null && content.length() > MAX_DOCUMENT_CHARS;
    String text =
        content == null
            ? "Document content is unavailable; re-index the repository to store it."
            : truncated ? content.substring(0, MAX_DOCUMENT_CHARS) : content;
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("repo", repository.owner() + "/" + repository.name());
    output.put("path", row.pathOrRef());
    output.put("title", row.title());
    output.put("url", row.url());
    output.put("kind", row.kind().name());
    output.put("truncated", truncated);
    output.put("content", untrusted(text));
    return output;
  }

  private RepositoryRow repository(String ref) {
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("repo is required; use owner/name from list_repositories");
    }
    String value = ref.strip();
    Optional<RepositoryRow> repository;
    if (value.chars().allMatch(Character::isDigit)) {
      repository = repositories.findById(Long.parseLong(value));
    } else {
      String[] parts = value.split("/");
      if (parts.length != 2) {
        throw new IllegalArgumentException("repo must be owner/name or a numeric id; got " + ref);
      }
      repository = repositories.findByOwnerAndName(parts[0], parts[1]);
    }
    return repository.orElseThrow(() ->
        new IllegalArgumentException("Unknown repository: " + ref + "; call list_repositories"));
  }

  private static String untrusted(String text) {
    return UNTRUSTED_BEGIN + "\n" + (text == null ? "" : text) + "\n" + UNTRUSTED_END;
  }
}
