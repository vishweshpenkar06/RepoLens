package com.vishwesh.repolens.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vishwesh.repolens.TestcontainersConfiguration;
import com.vishwesh.repolens.ingest.DocumentKind;
import com.vishwesh.repolens.support.FakeGitHubClient;
import com.vishwesh.repolens.support.TestFakes;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.ai.model.embedding=none",
      "repolens.embedding.warmup=false",
      "repolens.security.api-key-required=false"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestFakes.class})
class RepoControllerTests {

  @Autowired private MockMvc mvc;
  @Autowired private FakeGitHubClient github;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
    github.reset();
    github.putMarkdown(DocumentKind.README, "README.md", "# Demo\n\nHello world", "\"r1\"");
  }

  @Test
  void rejectsInvalidRepositoryUrls() throws Exception {
    for (String url :
        new String[] {
          "http://github.com/acme/demo",
          "https://github.com.evil.com/acme/demo",
          "https://github.com/acme/demo/extra"
        }) {
      mvc.perform(
              post("/api/v1/repos")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\":\"" + url + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Invalid repository URL"));
    }
  }

  @Test
  void rejectsBlankUrl() throws Exception {
    mvc.perform(
            post("/api/v1/repos").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createsRepositoryAndCompletesIngestion() throws Exception {
    mvc.perform(
            post("/api/v1/repos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/acme/demo\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.repoId").isNumber())
        .andExpect(jsonPath("$.jobId").isNumber());

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            mvc.perform(get("/api/v1/repos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.latestJob.status").value("SUCCEEDED"))
                .andExpect(
                    jsonPath("$.latestJob.chunksWritten")
                        .value(org.hamcrest.Matchers.greaterThan(0))));

    mvc.perform(get("/api/v1/repos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].slug").value("acme/demo"));
  }

  @Test
  void rejectsDuplicateRepository() throws Exception {
    String body = "{\"url\":\"https://github.com/acme/demo\"}";
    mvc.perform(post("/api/v1/repos").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            mvc.perform(get("/api/v1/repos/1")).andExpect(jsonPath("$.status").value("READY")));

    mvc.perform(post("/api/v1/repos").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Repository already registered"));
  }

  @Test
  void reindexStartsNewJob() throws Exception {
    mvc.perform(
            post("/api/v1/repos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/acme/demo\"}"))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            mvc.perform(get("/api/v1/repos/1")).andExpect(jsonPath("$.status").value("READY")));

    mvc.perform(post("/api/v1/repos/1/reindex"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(2));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            mvc.perform(get("/api/v1/repos/1"))
                .andExpect(jsonPath("$.latestJob.id").value(2))
                .andExpect(jsonPath("$.latestJob.status").value("SUCCEEDED")));
  }

  @Test
  void returnsNotFoundForUnknownRepository() throws Exception {
    mvc.perform(get("/api/v1/repos/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Repository not found"));
  }

  @Test
  void rejectsMalformedJson() throws Exception {
    mvc.perform(post("/api/v1/repos").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void healthEndpointIsPublic() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
