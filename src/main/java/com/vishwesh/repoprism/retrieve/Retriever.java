package com.vishwesh.repoprism.retrieve;

import com.vishwesh.repoprism.config.RepoPrismProperties;
import com.vishwesh.repoprism.embed.Embedder;
import com.vishwesh.repoprism.observability.RepoPrismMetrics;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/** Runs vector and keyword retrieval in parallel on virtual threads and fuses the results. */
@Service
public class Retriever {

  private static final long STAGE_TIMEOUT_SECONDS = 15;

  private final VectorSearch vectorSearch;
  private final KeywordSearch keywordSearch;
  private final RankFusion rankFusion;
  private final Embedder embedder;
  private final RepoPrismProperties properties;
  private final RepoPrismMetrics metrics;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public Retriever(
      VectorSearch vectorSearch,
      KeywordSearch keywordSearch,
      RankFusion rankFusion,
      Embedder embedder,
      RepoPrismProperties properties,
      RepoPrismMetrics metrics) {
    this.vectorSearch = vectorSearch;
    this.keywordSearch = keywordSearch;
    this.rankFusion = rankFusion;
    this.embedder = embedder;
    this.properties = properties;
    this.metrics = metrics;
  }

  public List<FusedHit> retrieve(long repositoryId, String query, int requestedLimit) {
    int limit = clampLimit(requestedLimit);
    int candidates = Math.max(limit, properties.retrieval().candidates());
    float[] embedding = embedder.embed(query);

    Future<List<ScoredChunk>> vector =
        executor.submit(() ->
            metrics.timeRetrieval(
                "vector", () -> vectorSearch.search(repositoryId, embedding, candidates)));
    Future<List<ScoredChunk>> keyword =
        executor.submit(() ->
            metrics.timeRetrieval(
                "keyword", () -> keywordSearch.search(repositoryId, query, candidates)));

    try {
      List<ScoredChunk> vectorResults = vector.get(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      List<ScoredChunk> keywordResults = keyword.get(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return metrics.timeRetrieval(
          "fuse", () -> rankFusion.fuse(vectorResults, keywordResults, limit));
    } catch (TimeoutException e) {
      vector.cancel(true);
      keyword.cancel(true);
      throw new RetrievalException("Retrieval timed out", e);
    } catch (InterruptedException e) {
      vector.cancel(true);
      keyword.cancel(true);
      Thread.currentThread().interrupt();
      throw new RetrievalException("Retrieval interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RetrievalException("Retrieval failed", cause);
    }
  }

  public int clampLimit(int requestedLimit) {
    int max = Math.max(1, properties.ask().topKMax());
    if (requestedLimit <= 0) {
      return Math.min(max, properties.retrieval().topK());
    }
    return Math.min(requestedLimit, max);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
