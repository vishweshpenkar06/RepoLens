package com.vishwesh.repoprism.ingest;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runs ingestion jobs on virtual threads, at most one per repository. */
@Component
public class IngestionJobRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestionJobRunner.class);

  private final IngestionService ingestionService;
  private final Set<Long> running = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public IngestionJobRunner(IngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  public boolean isRunning(long repositoryId) {
    return running.contains(repositoryId);
  }

  public void submit(long repositoryId, long jobId) {
    if (!running.add(repositoryId)) {
      throw new IllegalStateException("An ingestion is already running for this repository");
    }
    executor.submit(() -> {
      try {
        ingestionService.ingest(repositoryId, jobId);
      } catch (RuntimeException e) {
        log.warn("Ingestion job {} for repository {} failed", jobId, repositoryId, e);
      } finally {
        running.remove(repositoryId);
      }
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
