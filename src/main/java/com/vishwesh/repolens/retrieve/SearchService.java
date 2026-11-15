package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.api.RepositoryNotFoundException;
import com.vishwesh.repolens.config.RepoLensProperties;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

  private final RepositoryStore repositories;
  private final Retriever retriever;
  private final RepoLensProperties properties;

  public SearchService(
      RepositoryStore repositories, Retriever retriever, RepoLensProperties properties) {
    this.repositories = repositories;
    this.retriever = retriever;
    this.properties = properties;
  }

  public List<SearchResult> search(long repositoryId, String query, int requestedLimit) {
    if (repositories.findById(repositoryId).isEmpty()) {
      throw new RepositoryNotFoundException(repositoryId);
    }
    if (query == null || query.isBlank()) {
      throw new InvalidSearchQueryException("Query parameter q is required");
    }
    String trimmed = query.strip();
    if (trimmed.length() > properties.ask().maxQuestionLength()) {
      throw new InvalidSearchQueryException(
          "Query must be at most " + properties.ask().maxQuestionLength() + " characters");
    }
    return retriever.retrieve(repositoryId, trimmed, requestedLimit).stream()
        .map(SearchResult::from)
        .toList();
  }
}
