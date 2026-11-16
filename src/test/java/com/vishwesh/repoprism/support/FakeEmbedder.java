package com.vishwesh.repoprism.support;

import com.vishwesh.repoprism.embed.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic, dependency-free embedder for tests. */
public class FakeEmbedder implements Embedder {

  public static final int DIMENSIONS = 384;

  private final AtomicInteger calls = new AtomicInteger();

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  @Override
  public boolean ready() {
    return true;
  }

  public int callCount() {
    return calls.get();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    calls.incrementAndGet();
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (String text : texts) {
      vectors.add(vectorFor(text));
    }
    return vectors;
  }

  public static float[] vectorFor(String text) {
    Random random = new Random(text.hashCode());
    float[] vector = new float[DIMENSIONS];
    double norm = 0;
    for (int i = 0; i < DIMENSIONS; i++) {
      vector[i] = random.nextFloat() - 0.5f;
      norm += vector[i] * (double) vector[i];
    }
    if (norm > 0) {
      float scale = (float) (1.0 / Math.sqrt(norm));
      for (int i = 0; i < DIMENSIONS; i++) {
        vector[i] *= scale;
      }
    }
    return vector;
  }
}
