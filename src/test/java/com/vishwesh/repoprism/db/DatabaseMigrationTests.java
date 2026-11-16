package com.vishwesh.repoprism.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.vishwesh.repoprism.TestcontainersConfiguration;
import com.vishwesh.repoprism.support.TestFakes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.ai.model.embedding=none", "repoprism.embedding.warmup=false"})
@Import({TestcontainersConfiguration.class, TestFakes.class})
class DatabaseMigrationTests {

  @Autowired private JdbcClient jdbcClient;

  @Test
  void createsVectorExtension() {
    Boolean installed =
        jdbcClient
            .sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
            .query(Boolean.class)
            .single();
    assertThat(installed).isTrue();
  }

  @Test
  void createsAllTables() {
    List<String> tables =
        jdbcClient
            .sql(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """)
            .query(String.class)
            .list();
    assertThat(tables)
        .contains(
            "api_key",
            "chunk",
            "document",
            "flyway_schema_history",
            "ingestion_job",
            "query_log",
            "repository");
  }

  @Test
  void embeddingColumnIsVector384() {
    String type =
        jdbcClient
            .sql(
                """
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname = 'chunk' AND a.attname = 'embedding'
                """)
            .query(String.class)
            .single();
    assertThat(type).isEqualTo("vector(384)");
  }

  @Test
  void hnswIndexExists() {
    Boolean exists =
        jdbcClient
            .sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM pg_indexes
                  WHERE tablename = 'chunk' AND indexname = 'chunk_vec_idx'
                )
                """)
            .query(Boolean.class)
            .single();
    assertThat(exists).isTrue();
  }
}
