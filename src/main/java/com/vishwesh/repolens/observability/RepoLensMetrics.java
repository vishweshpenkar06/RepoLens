package com.vishwesh.repolens.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Central Micrometer metrics for RepoLens (Prometheus names use underscores). */
@Component
public class RepoLensMetrics {

  private final MeterRegistry registry;
  private final Counter cacheHits;
  private final Counter cacheMisses;
  private final Counter llmErrors;
  private final Counter ingestDocs;
  private final Counter ingestChunks;

  public RepoLensMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.cacheHits = Counter.builder("repolens.cache.hits").register(registry);
    this.cacheMisses = Counter.builder("repolens.cache.misses").register(registry);
    this.llmErrors = Counter.builder("repolens.llm.errors").register(registry);
    this.ingestDocs = Counter.builder("repolens.ingest.docs").register(registry);
    this.ingestChunks = Counter.builder("repolens.ingest.chunks").register(registry);
  }

  public void recordAsk(Duration latency, boolean cached, boolean refused) {
    Timer.builder("repolens.ask.latency")
        .tag("cached", Boolean.toString(cached))
        .tag("refused", Boolean.toString(refused))
        .register(registry)
        .record(latency);
  }

  public void recordRetrieval(String stage, Duration latency) {
    Timer.builder("repolens.retrieval.latency")
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
      Timer.builder("repolens.llm.latency")
          .register(registry)
          .record(Duration.ofNanos(System.nanoTime() - start));
    }
  }

  public void llmError() {
    llmErrors.increment();
  }

  public void cacheHit() {
    cacheHits.increment();
  }

  public void cacheMiss() {
    cacheMisses.increment();
  }

  public void rateLimitRejected(String scope) {
    Counter.builder("repolens.ratelimit.rejected")
        .tag("scope", scope)
        .register(registry)
        .increment();
  }

  public void recordIngest(int documents, int chunks, Duration duration) {
    ingestDocs.increment(documents);
    ingestChunks.increment(chunks);
    Timer.builder("repolens.ingest.duration").register(registry).record(duration);
  }
}
