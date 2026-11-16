package com.vishwesh.repoprism.answer;

import com.vishwesh.repoprism.api.RepositoryNotFoundException;
import com.vishwesh.repoprism.config.RepoPrismProperties;
import com.vishwesh.repoprism.ingest.RepositoryRow;
import com.vishwesh.repoprism.ingest.store.RepositoryStore;
import com.vishwesh.repoprism.observability.RepoPrismMetrics;
import com.vishwesh.repoprism.retrieve.FusedHit;
import com.vishwesh.repoprism.retrieve.Retriever;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Grounded question answering: retrieve, prompt, generate, verify citations, cache, log. */
@Service
public class AskService {

  private static final Logger log = LoggerFactory.getLogger(AskService.class);
  private static final Pattern TOKEN = Pattern.compile("\\S+\\s*");

  private final RepositoryStore repositories;
  private final Retriever retriever;
  private final PromptBuilder promptBuilder;
  private final LlmGateway llmGateway;
  private final AnswerParser answerParser;
  private final CitationVerifier citationVerifier;
  private final AskCache cache;
  private final QueryLogStore queryLog;
  private final RepoPrismProperties properties;
  private final RepoPrismMetrics metrics;

  public AskService(
      RepositoryStore repositories,
      Retriever retriever,
      PromptBuilder promptBuilder,
      LlmGateway llmGateway,
      AnswerParser answerParser,
      CitationVerifier citationVerifier,
      AskCache cache,
      QueryLogStore queryLog,
      RepoPrismProperties properties,
      RepoPrismMetrics metrics) {
    this.repositories = repositories;
    this.retriever = retriever;
    this.promptBuilder = promptBuilder;
    this.llmGateway = llmGateway;
    this.answerParser = answerParser;
    this.citationVerifier = citationVerifier;
    this.cache = cache;
    this.queryLog = queryLog;
    this.properties = properties;
    this.metrics = metrics;
  }

  public AnswerResult ask(long repositoryId, String question, Integer requestedTopK) {
    long started = System.nanoTime();
    RepositoryRow repository = repository(repositoryId);
    String normalized = validate(question);
    int topK = clampTopK(requestedTopK);
    String cacheKey = AskCache.key(repository.id(), repository.contentVersion(), normalized, topK);

    Optional<CachedAnswer> cached = cache.get(cacheKey);
    if (cached.isPresent()) {
      metrics.cacheHit();
      long latency = elapsedMillis(started);
      metrics.recordAsk(java.time.Duration.ofMillis(latency), true, cached.get().refused());
      record(repository.id(), normalized, List.of(), latency, true, cached.get().refused());
      return result(cached.get(), latency, true);
    }
    metrics.cacheMiss();

    List<FusedHit> hits = retriever.retrieve(repository.id(), normalized, topK);
    VerifiedAnswer verified;
    if (hits.isEmpty()) {
      verified = citationVerifier.refusal();
    } else {
      LlmRequest request = promptBuilder.build(normalized, hits);
      ParsedAnswer parsed = answerParser.parse(metrics.timeLlm(() -> llmGateway.complete(request)));
      verified = citationVerifier.verify(parsed, hits);
    }

    CachedAnswer payload =
        new CachedAnswer(verified.answer(), verified.citations(), verified.refused());
    cache.put(cacheKey, payload);
    long latency = elapsedMillis(started);
    metrics.recordAsk(java.time.Duration.ofMillis(latency), false, verified.refused());
    record(repository.id(), normalized, chunkIds(hits), latency, false, verified.refused());
    return result(payload, latency, false);
  }

  public void stream(
      long repositoryId,
      String question,
      Integer requestedTopK,
      Consumer<String> onToken,
      Consumer<AnswerResult> onComplete) {
    long started = System.nanoTime();
    RepositoryRow repository = repository(repositoryId);
    String normalized = validate(question);
    int topK = clampTopK(requestedTopK);
    String cacheKey = AskCache.key(repository.id(), repository.contentVersion(), normalized, topK);

    Optional<CachedAnswer> cached = cache.get(cacheKey);
    if (cached.isPresent()) {
      metrics.cacheHit();
      emitTokens(cached.get().answer(), onToken);
      long latency = elapsedMillis(started);
      metrics.recordAsk(java.time.Duration.ofMillis(latency), true, cached.get().refused());
      record(repository.id(), normalized, List.of(), latency, true, cached.get().refused());
      onComplete.accept(result(cached.get(), latency, true));
      return;
    }

    List<FusedHit> hits = retriever.retrieve(repository.id(), normalized, topK);
    if (hits.isEmpty()) {
      metrics.cacheMiss();
      VerifiedAnswer refusal = citationVerifier.refusal();
      emitTokens(refusal.answer(), onToken);
      long latency = elapsedMillis(started);
      metrics.recordAsk(java.time.Duration.ofMillis(latency), false, true);
      record(repository.id(), normalized, List.of(), latency, false, true);
      onComplete.accept(
          new AnswerResult(refusal.answer(), refusal.citations(), true, latency, false));
      return;
    }
    metrics.cacheMiss();

    StringBuilder raw = new StringBuilder();
    LlmRequest request = promptBuilder.buildStreaming(normalized, hits);
    long llmStart = System.nanoTime();
    try {
      llmGateway.stream(
          request,
          token -> {
            raw.append(token);
            onToken.accept(token);
          });
    } catch (RuntimeException e) {
      metrics.llmError();
      throw e;
    }
    metrics.recordRetrieval("llm-stream", java.time.Duration.ofNanos(System.nanoTime() - llmStart));
    VerifiedAnswer verified = citationVerifier.verifyStreaming(raw.toString(), hits);
    CachedAnswer payload =
        new CachedAnswer(verified.answer(), verified.citations(), verified.refused());
    cache.put(cacheKey, payload);
    long latency = elapsedMillis(started);
    metrics.recordAsk(java.time.Duration.ofMillis(latency), false, verified.refused());
    record(repository.id(), normalized, chunkIds(hits), latency, false, verified.refused());
    onComplete.accept(
        new AnswerResult(
            verified.answer(), verified.citations(), verified.refused(), latency, false));
  }

  private RepositoryRow repository(long repositoryId) {
    return repositories
        .findById(repositoryId)
        .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
  }

  private String validate(String question) {
    if (question == null || question.isBlank()) {
      throw new InvalidQuestionException("question is required");
    }
    String trimmed = question.strip();
    if (trimmed.length() > properties.ask().maxQuestionLength()) {
      throw new InvalidQuestionException(
          "question must be at most " + properties.ask().maxQuestionLength() + " characters");
    }
    return trimmed;
  }

  private int clampTopK(Integer requestedTopK) {
    int max = Math.max(1, properties.ask().topKMax());
    if (requestedTopK == null || requestedTopK <= 0) {
      return Math.min(max, properties.retrieval().topK());
    }
    return Math.min(requestedTopK, max);
  }

  private void record(
      long repositoryId,
      String question,
      List<Long> chunkIds,
      long latencyMs,
      boolean cached,
      boolean refused) {
    try {
      queryLog.record(repositoryId, question, chunkIds, latencyMs, cached, refused);
    } catch (RuntimeException e) {
      log.warn("Could not write query log for repository {}", repositoryId, e);
    }
  }

  private static List<Long> chunkIds(List<FusedHit> hits) {
    return hits.stream().map(hit -> hit.chunk().chunkId()).toList();
  }

  private static AnswerResult result(CachedAnswer cached, long latency, boolean hit) {
    return new AnswerResult(cached.answer(), cached.citations(), cached.refused(), latency, hit);
  }

  private static void emitTokens(String answer, Consumer<String> onToken) {
    Matcher matcher = TOKEN.matcher(answer == null ? "" : answer);
    while (matcher.find()) {
      onToken.accept(matcher.group());
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
