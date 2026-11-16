package com.vishwesh.repoprism.answer;

import com.vishwesh.repoprism.config.RepoPrismProperties;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic LLM used by tests, local development without a key, and Phase 5 load tests. Tests
 * can enqueue exact responses; otherwise a grounded default citing context block [1] is returned.
 */
@Component
@ConditionalOnProperty(name = "repoprism.llm.provider", havingValue = "fake", matchIfMissing = true)
public class FakeLlmGateway implements LlmGateway {

  public static final String DEFAULT_COMPLETION =
      "{\"answer\":\"Based on the provided context [1], this is a fake grounded answer.\","
          + "\"citations\":[1],\"refused\":false}";
  public static final String DEFAULT_STREAM_ANSWER =
      "Based on the provided context [1], this is a fake grounded answer.";

  private final Queue<String> completions = new ConcurrentLinkedQueue<>();
  private final Queue<String> streamAnswers = new ConcurrentLinkedQueue<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final Duration latency;

  public FakeLlmGateway(RepoPrismProperties properties) {
    this.latency = properties.llm().fakeLatency();
  }

  public FakeLlmGateway enqueueCompletion(String response) {
    completions.add(response);
    return this;
  }

  public FakeLlmGateway enqueueStream(String answer) {
    streamAnswers.add(answer);
    return this;
  }

  public void reset() {
    completions.clear();
    streamAnswers.clear();
    calls.set(0);
  }

  public int callCount() {
    return calls.get();
  }

  @Override
  public String complete(LlmRequest request) {
    calls.incrementAndGet();
    sleep();
    String response = completions.poll();
    return response == null ? DEFAULT_COMPLETION : response;
  }

  @Override
  public void stream(LlmRequest request, Consumer<String> onToken) {
    calls.incrementAndGet();
    sleep();
    String answer = streamAnswers.poll();
    String text = answer == null ? DEFAULT_STREAM_ANSWER : answer;
    Matcher matcher = Pattern.compile("\\S+\\s*").matcher(text);
    while (matcher.find()) {
      onToken.accept(matcher.group());
    }
  }

  @Override
  public String providerName() {
    return "fake";
  }

  private void sleep() {
    if (latency.isZero() || latency.isNegative()) {
      return;
    }
    try {
      Thread.sleep(latency);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while simulating LLM latency", e);
    }
  }
}
