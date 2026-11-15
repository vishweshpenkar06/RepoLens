package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Cosine-similarity search over {@code chunk.embedding} (pgvector HNSW index). */
@Repository
public class VectorSearch {

  private final JdbcClient jdbc;

  public VectorSearch(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<ScoredChunk> search(long repositoryId, float[] queryEmbedding, int limit) {
    if (limit <= 0 || queryEmbedding == null || queryEmbedding.length == 0) {
      return List.of();
    }
    String vector = ChunkStore.toVectorLiteral(queryEmbedding);
    return jdbc.sql(
            """
            SELECT c.id AS chunk_id, c.document_id, d.kind, d.path_or_ref, d.url, d.title,
                   c.heading_path, c.content,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.repository_id = ?
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """)
        .params(vector, repositoryId, vector, limit)
        .query((rs, rowNum) ->
            new ScoredChunk(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                DocumentKind.valueOf(rs.getString("kind")),
                rs.getString("path_or_ref"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("heading_path"),
                rs.getString("content"),
                rs.getDouble("score")))
        .list();
  }
}
