package com.vishwesh.repoprism.retrieve;

import com.vishwesh.repoprism.ingest.DocumentKind;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Full-text search over the generated {@code chunk.fts} column using {@code websearch_to_tsquery}.
 */
@Repository
public class KeywordSearch {

  private final JdbcClient jdbc;

  public KeywordSearch(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<ScoredChunk> search(long repositoryId, String query, int limit) {
    if (limit <= 0 || query == null || query.isBlank()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT c.id AS chunk_id, c.document_id, d.kind, d.path_or_ref, d.url, d.title,
                   c.heading_path, c.content,
                   ts_rank_cd(c.fts, websearch_to_tsquery('english', ?)) AS score
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.repository_id = ?
              AND c.fts @@ websearch_to_tsquery('english', ?)
            ORDER BY score DESC, c.id ASC
            LIMIT ?
            """)
        .params(query, repositoryId, query, limit)
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
