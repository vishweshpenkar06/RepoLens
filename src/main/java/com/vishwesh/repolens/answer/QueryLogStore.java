package com.vishwesh.repolens.answer;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QueryLogStore {

  private final JdbcClient jdbc;

  public QueryLogStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(
      long repositoryId,
      String question,
      List<Long> chunkIds,
      long latencyMs,
      boolean cached,
      boolean refused) {
    String array =
        chunkIds.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    jdbc.sql(
            """
            INSERT INTO query_log
              (repository_id, question, retrieved_chunk_ids, latency_ms, cached, refused)
            VALUES (?, ?, ?::bigint[], ?, ?, ?)
            """)
        .params(
            repositoryId,
            question,
            array,
            (int) Math.min(Integer.MAX_VALUE, latencyMs),
            cached,
            refused)
        .update();
  }
}
