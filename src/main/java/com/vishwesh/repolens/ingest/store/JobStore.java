package com.vishwesh.repolens.ingest.store;

import com.vishwesh.repolens.ingest.JobRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobStore {

  private static final int MAX_ERROR_LENGTH = 4000;

  private final JdbcClient jdbc;

  public JobStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long create(long repositoryId) {
    return jdbc.sql(
            """
            INSERT INTO ingestion_job (repository_id, status, started_at)
            VALUES (?, 'RUNNING', now())
            RETURNING id
            """)
        .param(repositoryId)
        .query(Long.class)
        .single();
  }

  public void succeed(long jobId, int docsSeen, int chunksWritten) {
    jdbc.sql(
            """
            UPDATE ingestion_job
            SET status = 'SUCCEEDED', finished_at = now(), docs_seen = ?, chunks_written = ?
            WHERE id = ?
            """)
        .params(docsSeen, chunksWritten, jobId)
        .update();
  }

  public void fail(long jobId, String error) {
    String message = error == null ? "unknown error" : error;
    if (message.length() > MAX_ERROR_LENGTH) {
      message = message.substring(0, MAX_ERROR_LENGTH);
    }
    jdbc.sql(
            """
            UPDATE ingestion_job
            SET status = 'FAILED', finished_at = now(), error = ?
            WHERE id = ?
            """)
        .params(message, jobId)
        .update();
  }

  public Optional<JobRow> findById(long id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::map).optional();
  }

  public Optional<JobRow> latestForRepository(long repositoryId) {
    return jdbc.sql(SELECT + " WHERE repository_id = ? ORDER BY id DESC LIMIT 1")
        .param(repositoryId)
        .query(this::map)
        .optional();
  }

  private static final String SELECT =
      """
      SELECT id, repository_id, status, started_at, finished_at, docs_seen, chunks_written, error
      FROM ingestion_job
      """;

  private JobRow map(ResultSet rs, int rowNum) throws SQLException {
    return new JobRow(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getString("status"),
        RepositoryStore.toInstant(rs.getObject("started_at", OffsetDateTime.class)),
        RepositoryStore.toInstant(rs.getObject("finished_at", OffsetDateTime.class)),
        rs.getInt("docs_seen"),
        rs.getInt("chunks_written"),
        rs.getString("error"));
  }
}
