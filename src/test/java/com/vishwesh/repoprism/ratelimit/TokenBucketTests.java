package com.vishwesh.repoprism.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTests {

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

  @Test
  void startsFullAndConsumesTokens() {
    TokenBucket bucket = new TokenBucket(3, 1, nanos::get);

    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isFalse();
    assertThat(bucket.remaining()).isZero();
  }

  @Test
  void refillsOverTimeUpToCapacity() {
    TokenBucket bucket = new TokenBucket(2, 1, nanos::get);
    bucket.tryAcquire();
    bucket.tryAcquire();
    assertThat(bucket.tryAcquire()).isFalse();

    nanos.addAndGet(1_000_000_000L);
    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isFalse();

    nanos.addAndGet(60_000_000_000L);
    assertThat(bucket.remaining()).isEqualTo(2);
    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isTrue();
    assertThat(bucket.tryAcquire()).isFalse();
  }

  @Test
  void supportsFractionalRefill() {
    TokenBucket bucket = new TokenBucket(1, 0.5, nanos::get);
    bucket.tryAcquire();

    nanos.addAndGet(1_000_000_000L);
    assertThat(bucket.tryAcquire()).isFalse();

    nanos.addAndGet(1_000_000_000L);
    assertThat(bucket.tryAcquire()).isTrue();
  }

  @Test
  void reportsRetryAfterUntilNextToken() {
    TokenBucket bucket = new TokenBucket(2, 0.5, nanos::get);
    bucket.tryAcquire();
    bucket.tryAcquire();

    assertThat(bucket.retryAfterSeconds()).isEqualTo(2);
    nanos.addAndGet(1_000_000_000L);
    assertThat(bucket.retryAfterSeconds()).isEqualTo(1);
    nanos.addAndGet(1_000_000_000L);
    assertThat(bucket.retryAfterSeconds()).isZero();
  }

  @Test
  void ignoresBackwardsTime() {
    TokenBucket bucket = new TokenBucket(1, 1, nanos::get);
    bucket.tryAcquire();

    nanos.addAndGet(-5_000_000_000L);
    assertThat(bucket.tryAcquire()).isFalse();
    assertThat(bucket.remaining()).isZero();
  }

  @Test
  void isThreadSafeUnderContention() throws Exception {
    TokenBucket bucket = new TokenBucket(100, 1, () -> 1_000_000_000L);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    AtomicInteger successes = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(200);

    for (int i = 0; i < 200; i++) {
      executor.submit(() -> {
        try {
          start.await();
          if (bucket.tryAcquire()) {
            successes.incrementAndGet();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdownNow();

    assertThat(successes.get()).isEqualTo(100);
    assertThat(bucket.remaining()).isZero();
  }

  @Test
  void validatesConstructorArguments() {
    assertThatThrownBy(() -> new TokenBucket(0, 1, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(1, 0, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(1, -1, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(1, Double.NaN, System::nanoTime))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
