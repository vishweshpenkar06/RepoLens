package com.vishwesh.repoprism.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vishwesh.repoprism.TestcontainersConfiguration;
import com.vishwesh.repoprism.support.TestFakes;
import jakarta.servlet.http.HttpServletResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.ai.model.embedding=none",
      "repoprism.embedding.warmup=false",
      "repoprism.security.api-key-required=true",
      "repoprism.security.bootstrap-api-key=test-secret-key",
      "repoprism.security.api-key-cache-ttl=0s",
      "repoprism.rate-limit.enabled=false"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestFakes.class})
class SecurityFilterTests {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
    jdbc.sql("UPDATE api_key SET revoked = false").update();
  }

  @Test
  void rejectsMissingApiKey() throws Exception {
    mvc.perform(get("/api/v1/repos"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  void rejectsWrongApiKey() throws Exception {
    mvc.perform(get("/api/v1/repos").header(ApiKeyFilter.HEADER, "nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void acceptsValidApiKey() throws Exception {
    mvc.perform(get("/api/v1/repos").header(ApiKeyFilter.HEADER, "test-secret-key"))
        .andExpect(status().isOk());
  }

  @Test
  void excludesActuatorEndpoints() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void rejectsRevokedApiKey() throws Exception {
    jdbc.sql("UPDATE api_key SET revoked = true WHERE label = 'bootstrap'").update();

    mvc.perform(get("/api/v1/repos").header(ApiKeyFilter.HEADER, "test-secret-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exposesPrometheusMetricsWithoutApiKey() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(Matchers.containsString("repoprism_cache_hits_total")));
  }

  @Test
  void storesOnlyTheHash() {
    String stored =
        jdbc.sql("SELECT key_hash FROM api_key WHERE label = 'bootstrap'")
            .query(String.class)
            .single();

    assertThat(stored).isNotEqualTo("test-secret-key").hasSize(64);
    assertThat(stored).isEqualTo(ApiKeyService.hash("test-secret-key"));
  }

  @Test
  void propagatesSafeRequestIdAndGeneratesForUnsafeValues() throws Exception {
    mvc.perform(
            get("/api/v1/repos")
                .header(ApiKeyFilter.HEADER, "test-secret-key")
                .header(RequestIdFilter.HEADER, "req-123"))
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.HEADER, "req-123"));

    String generated =
        mvc.perform(
                get("/api/v1/repos")
                    .header(ApiKeyFilter.HEADER, "test-secret-key")
                    .header(RequestIdFilter.HEADER, "bad id with spaces"))
            .andReturn()
            .getResponse()
            .getHeader(RequestIdFilter.HEADER);
    assertThat(generated).matches("[0-9a-f-]{36}");
    assertThat(HttpServletResponse.SC_OK).isEqualTo(200);
  }
}
