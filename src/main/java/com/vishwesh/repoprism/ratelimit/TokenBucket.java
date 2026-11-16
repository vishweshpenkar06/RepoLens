package com.vishwesh.repoprism.ratelimit;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Thread-safe token bucket with an injectable time source.
 *
 * <h2>HUMAN CHECKPOINT 3 — delegated by the owner to the agent</h2>
 *
 * <p>Provenance: the owner delegated this checkpoint to the agent on 2026-11-15; this is
 * agent-written code. It uses a {@link ReentrantLock} rather than {@code synchronized} so no
 * monitor is ever held across blocking work, and takes nanosecond time from a {@link LongSupplier}
 * so tests can advance time deterministically.
 *
 * <p>Behaviour: starts full, refills continuously at {@code refillPerSecond} up to {@code
 * capacity}, and {@link #tryAcquire()} consumes exactly one token when available.
 */
public final class TokenBucket {

  private final long capacity;
  private final double refillPerSecond;
  private final LongSupplier nanoClock;
  private final ReentrantLock lock = new ReentrantLock();

  private double tokens;
  private long lastRefillNanos;

  public TokenBucket(long capacity, double refillPerSecond, LongSupplier nanoClock) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    if (refillPerSecond <= 0
        || Double.isNaN(refillPerSecond)
        || Double.isInfinite(refillPerSecond)) {
      throw new IllegalArgumentException("refillPerSecond must be a positive finite number");
    }
    this.capacity = capacity;
    this.refillPerSecond = refillPerSecond;
    this.nanoClock = nanoClock;
    this.tokens = capacity;
    this.lastRefillNanos = nanoClock.getAsLong();
  }

  public boolean tryAcquire() {
    lock.lock();
    try {
      refill();
      if (tokens >= 1.0) {
        tokens -= 1.0;
        return true;
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  public long capacity() {
    return capacity;
  }

  public long remaining() {
    lock.lock();
    try {
      refill();
      return (long) Math.floor(tokens);
    } finally {
      lock.unlock();
    }
  }

  /** Whole seconds until one token is available; 0 when a token is available now. */
  public long retryAfterSeconds() {
    lock.lock();
    try {
      refill();
      if (tokens >= 1.0) {
        return 0;
      }
      return (long) Math.ceil((1.0 - tokens) / refillPerSecond);
    } finally {
      lock.unlock();
    }
  }

  private void refill() {
    long now = nanoClock.getAsLong();
    long elapsedNanos = now - lastRefillNanos;
    if (elapsedNanos <= 0) {
      return;
    }
    double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
    tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
    lastRefillNanos = now;
  }
}
