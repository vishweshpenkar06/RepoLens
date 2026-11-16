package com.vishwesh.repoprism.ingest.store;

import com.vishwesh.repoprism.ingest.Chunk;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkStore {

  private final JdbcClient jdbc;

  public ChunkStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void deleteByDocument(long documentId) {
    jdbc.sql("DELETE FROM chunk WHERE document_id = ?").param(documentId).update();
  }

  public void insert(long documentId, long repositoryId, Chunk chunk, float[] embedding) {
    jdbc.sql(
            """
            INSERT INTO chunk (document_id, repository_id, ordinal, heading_path, content,
                               token_count, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector)
            """)
        .params(
            documentId,
            repositoryId,
            chunk.ordinal(),
            chunk.headingPath(),
            chunk.content(),
            chunk.tokenCount(),
            toVectorLiteral(embedding))
        .update();
  }

  public void insertAll(
      long documentId, long repositoryId, List<Chunk> chunks, List<float[]> embeddings) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException("Chunk and embedding counts differ");
    }
    for (int i = 0; i < chunks.size(); i++) {
      insert(documentId, repositoryId, chunks.get(i), embeddings.get(i));
    }
  }

  public long countByRepository(long repositoryId) {
    return jdbc.sql("SELECT count(*) FROM chunk WHERE repository_id = ?")
        .param(repositoryId)
        .query(Long.class)
        .single();
  }

  public static String toVectorLiteral(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 8 + 2);
    builder.append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(Float.toString(vector[i]));
    }
    builder.append(']');
    return builder.toString();
  }
}
