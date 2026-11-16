package com.vishwesh.repoprism.ingest.store;

import com.vishwesh.repoprism.ingest.DocumentKind;
import com.vishwesh.repoprism.ingest.DocumentRow;
import com.vishwesh.repoprism.ingest.DocumentSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentStore {

  private final JdbcClient jdbc;

  public DocumentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<DocumentRow> findByRepository(long repositoryId) {
    return jdbc.sql(SELECT + " WHERE repository_id = ? ORDER BY id")
        .param(repositoryId)
        .query(this::map)
        .list();
  }

  public Optional<DocumentRow> findById(long id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::map).optional();
  }

  public Optional<DocumentRow> findByRepositoryAndPathOrRef(long repositoryId, String pathOrRef) {
    return jdbc.sql(SELECT + " WHERE repository_id = ? AND path_or_ref = ? ORDER BY id LIMIT 1")
        .params(repositoryId, pathOrRef)
        .query(this::map)
        .optional();
  }

  public long upsert(long repositoryId, DocumentSource document, String contentHash) {
    return jdbc.sql(
            """
            INSERT INTO document
              (repository_id, kind, path_or_ref, url, title, content_hash, etag, content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (repository_id, kind, path_or_ref) DO UPDATE
            SET url = excluded.url,
                title = excluded.title,
                content_hash = excluded.content_hash,
                etag = excluded.etag,
                content = excluded.content,
                updated_at = now()
            RETURNING id
            """)
        .params(
            repositoryId,
            document.kind().name(),
            document.pathOrRef(),
            document.url(),
            document.title(),
            contentHash,
            document.etag(),
            document.content())
        .query(Long.class)
        .single();
  }

  public void deleteByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("DELETE FROM document WHERE id IN (:ids)").param("ids", ids).update();
  }

  private static final String SELECT =
      """
      SELECT id, repository_id, kind, path_or_ref, url, title, content_hash, etag, content
      FROM document
      """;

  private DocumentRow map(ResultSet rs, int rowNum) throws SQLException {
    return new DocumentRow(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        DocumentKind.valueOf(rs.getString("kind")),
        rs.getString("path_or_ref"),
        rs.getString("url"),
        rs.getString("title"),
        rs.getString("content_hash"),
        rs.getString("etag"),
        rs.getString("content"));
  }
}
