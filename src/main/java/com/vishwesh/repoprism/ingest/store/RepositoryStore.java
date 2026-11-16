package com.vishwesh.repoprism.ingest.store;

import com.vishwesh.repoprism.ingest.RepositoryRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositoryStore {

  private final JdbcClient jdbc;

  public RepositoryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(String owner, String name) {
    return jdbc.sql(
            "INSERT INTO repository (owner, name, status) VALUES (?, ?, 'PENDING') RETURNING id")
        .params(owner, name)
        .query(Long.class)
        .single();
  }

  public Optional<RepositoryRow> findById(long id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::map).optional();
  }

  public Optional<RepositoryRow> findByOwnerAndName(String owner, String name) {
    return jdbc.sql(SELECT + " WHERE owner = ? AND name = ?")
        .params(owner, name)
        .query(this::map)
        .optional();
  }

  public List<RepositoryRow> findAll() {
    return jdbc.sql(SELECT + " ORDER BY id").query(this::map).list();
  }

  public void updateStatus(long id, String status) {
    jdbc.sql("UPDATE repository SET status = ? WHERE id = ?").params(status, id).update();
  }

  public void markReady(long id, String defaultBranch, String contentVersion) {
    jdbc.sql(
            """
            UPDATE repository
            SET status = 'READY', default_branch = ?, last_ingested_at = now(), content_version = ?
            WHERE id = ?
            """)
        .params(defaultBranch, contentVersion, id)
        .update();
  }

  public void markFailed(long id) {
    updateStatus(id, "FAILED");
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM repository WHERE id = ?").param(id).update();
  }

  private static final String SELECT =
      """
      SELECT id, owner, name, default_branch, status, last_ingested_at, content_version, created_at
      FROM repository
      """;

  private RepositoryRow map(ResultSet rs, int rowNum) throws SQLException {
    return new RepositoryRow(
        rs.getLong("id"),
        rs.getString("owner"),
        rs.getString("name"),
        rs.getString("default_branch"),
        rs.getString("status"),
        toInstant(rs.getObject("last_ingested_at", OffsetDateTime.class)),
        rs.getString("content_version"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
