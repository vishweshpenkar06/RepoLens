package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Cosine-similarity search over {@code chunk.embedding} (pgvector HNSW index). */
@Repository
public class VectorSearch {

  private final HikariDataSource vectorDataSource;
  private final JdbcClient jdbc;

  public VectorSearch(
      DataSource primaryDataSource,
      DataSourceProperties properties,
      @Value("${repolens.retrieval.vector-pool-size:10}") int poolSize) {
    this.vectorDataSource = new HikariDataSource();
    if (primaryDataSource instanceof HikariDataSource hikari) {
      this.vectorDataSource.setJdbcUrl(hikari.getJdbcUrl());
      this.vectorDataSource.setUsername(hikari.getUsername());
      this.vectorDataSource.setPassword(hikari.getPassword());
      this.vectorDataSource.setDriverClassName(hikari.getDriverClassName());
    } else {
      this.vectorDataSource.setJdbcUrl(properties.getUrl());
      this.vectorDataSource.setUsername(properties.getUsername());
      this.vectorDataSource.setPassword(properties.getPassword());
    }
    this.vectorDataSource.setPoolName("VectorPool");
    this.vectorDataSource.setMaximumPoolSize(Math.max(1, poolSize));
    this.vectorDataSource.setMinimumIdle(2);
    this.vectorDataSource.setConnectionTimeout(5_000);
    // Iterative HNSW keeps returning rows while the repository filter is applied. The planner is
    // forced to use the vector index for this dedicated pool; the main pool still uses seq scan
    // for tiny tables and GIN bitmap scans for full-text search.
    this.vectorDataSource.setConnectionInitSql(
        "SET hnsw.iterative_scan=relaxed_order; SET enable_seqscan=off; SET enable_bitmapscan=off");
    this.jdbc = JdbcClient.create(vectorDataSource);
  }

  @PreDestroy
  void close() {
    vectorDataSource.close();
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
