package com.vishwesh.repoprism.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Central Micrometer metrics for RepoPrism (Prometheus names use underscores). */
@Component
public class RepoPrismMetrics {

  private final MeterRegistry registry;
  private final Counter cacheHits;
  private final Counter cacheMisses;
  private final Counter llmErrors;
  private final Counter ingestDocs;
  private final Counter ingestChunks;

  public RepoPrismMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.cacheHits = Counter.builder("repoprism.cache.hits").register(registry);
    this.cacheMisses = Counter.builder("repoprism.cache.misses").register(registry);
    this.llmErrors = Counter.builder("repoprism.llm.errors").register(registry);
    this.ingestDocs = Counter.builder("repoprism.ingest.docs").register(registry);
    this.ingestChunks = Counter.builder("repoprism.ingest.chunks").register(registry);
  }

  public void recordAsk(Duration latency, boolean cached, boolean refused) {
    Timer.builder("repoprism.ask.latency")
        .tag("cached", Boolean.toString(cached))
        .tag("refused", Boolean.toString(refused))
        .register(registry)
        .record(latency);
  }

  public void recordRetrieval(String stage, Duration latency) {
    Timer.builder("repoprism.retrieval.latency")
        .tag("stage", stage)
        .register(registry)
        .record(latency);
  }

  public <T> T timeRetrieval(String stage, Supplier<T> call) {
    long start = System.nanoTime();
    try {
      return call.get();
    } finally {
      recordRetrieval(stage, Duration.ofNanos(System.nanoTime() - start));
    }
  }

  public <T> T timeLlm(Supplier<T> call) {
    long start = System.nanoTime();
    try {
      return call.get();
    } catch (RuntimeException e) {
      llmErrors.increment();
      throw e;
    } finally {
      Timer.builder("repoprism.llm.latency")
          .register(registry)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
  }

  public void recordLlmStream(Duration latency) {
    Timer.builder("repoprism.llm.latency").register(registry).record(latency);
  }

  public void llmError() {
    llmErrors.increment();
  }

  public void recordEmbedding(Duration latency, boolean cached) {
    Timer.builder("repoprism.embedding.latency")
        .tag("cached", Boolean.toString(cached))
        .register(registry)
        .record(latency);
  }

  public void cacheHit() {
    cacheHits.increment();
  }

  public void cacheMiss() {
    cacheMisses.increment();
  }

  public void rateLimitRejected(String scope) {
    Counter.builder("repoprism.ratelimit.rejected")
        .tag("scope", scope)
        .register(registry)
        .increment();
  }

  public void recordIngest(int documents, int chunks, Duration duration) {
    ingestDocs.increment(documents);
    ingestChunks.increment(chunks);
    Timer.builder("repoprism.ingest.duration").register(registry).record(duration);
  }
}
