package com.vishwesh.repolens.embed;

import com.vishwesh.repolens.config.RepoLensProperties;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adapts the Spring AI ONNX Transformers model to the RepoLens {@link Embedder} interface. */
@Component
@ConditionalOnProperty(
    name = "spring.ai.model.embedding",
    havingValue = "transformers",
    matchIfMissing = true)
public class OnnxEmbedder implements Embedder {

  private static final Logger log = LoggerFactory.getLogger(OnnxEmbedder.class);
  private static final String WARMUP_TEXT = "RepoLens embedding warmup";

  private final EmbeddingModel embeddingModel;
  private final int dimensions;
  private final AtomicBoolean ready = new AtomicBoolean(false);

  public OnnxEmbedder(EmbeddingModel embeddingModel, RepoLensProperties properties) {
    this.embeddingModel = embeddingModel;
    this.dimensions = properties.embedding().dimensions();
  }

  @PostConstruct
  void warmUp() {
    try {
      List<float[]> vectors = embedAll(List.of(WARMUP_TEXT));
      if (vectors.isEmpty() || vectors.getFirst().length != dimensions) {
        throw new IllegalStateException(
            "Embedding model returned unexpected dimensions: "
                + (vectors.isEmpty() ? "none" : vectors.getFirst().length));
      }
      ready.set(true);
      log.info("ONNX embedding model ready ({} dims)", dimensions);
    } catch (RuntimeException e) {
      log.error("Embedding model warmup failed", e);
    }
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @Override
  public boolean ready() {
    return ready.get();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    List<float[]> vectors = embeddingModel.embed(texts);
    if (vectors.size() != texts.size()) {
      throw new IllegalStateException(
          "Embedding count mismatch: expected " + texts.size() + ", got " + vectors.size());
    }
    return vectors;
  }
}
