package com.vishwesh.repoprism.embed;

import java.util.List;

public interface Embedder {

  int dimensions();

  /** True once the underlying model is loaded and able to serve requests. */
  boolean ready();

  /** Embeds texts in order, returning one vector per input. */
  List<float[]> embedAll(List<String> texts);

  default float[] embed(String text) {
    return embedAll(List.of(text)).getFirst();
  }
}
