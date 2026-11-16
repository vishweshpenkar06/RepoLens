package com.vishwesh.repoprism.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vishwesh.repoprism.TestcontainersConfiguration;
import com.vishwesh.repoprism.ingest.store.ChunkStore;
import com.vishwesh.repoprism.ingest.store.DocumentStore;
import com.vishwesh.repoprism.ingest.store.JobStore;
import com.vishwesh.repoprism.ingest.store.RepositoryStore;
import com.vishwesh.repoprism.support.FakeEmbedder;
import com.vishwesh.repoprism.support.FakeGitHubClient;
import com.vishwesh.repoprism.support.TestFakes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"spring.ai.model.embedding=none", "repoprism.embedding.warmup=false"})
@Import({TestcontainersConfiguration.class, TestFakes.class})
class IngestionServiceTests {

  @Autowired private IngestionService ingestionService;
  @Autowired private RepositoryStore repositories;
  @Autowired private DocumentStore documents;
  @Autowired private ChunkStore chunks;
  @Autowired private JobStore jobs;
  @Autowired private FakeGitHubClient github;
  @Autowired private FakeEmbedder embedder;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE repository, ingestion_job RESTART IDENTITY CASCADE").update();
    github.reset();
  }

  @Test
  void ingestsDocumentsAndIssues() {
    github
        .putMarkdown(DocumentKind.README, "README.md", "# Demo\n\nHello world", "\"r1\"")
        .putMarkdown(DocumentKind.DOC, "docs/setup.md", "# Setup\n\nRun docker compose.", "\"s1\"")
        .issues(
            List.of(
                new DocumentSource(
                    DocumentKind.ISSUE,
                    "issue-7",
                    "https://github.com/acme/demo/issues/7",
                    "Bug",
                    "Something is broken",
                    null)));

    long[] ids = createRepo();
    ingestionService.ingest(ids[0], ids[1]);

    RepositoryRow repository = repositories.findById(ids[0]).orElseThrow();
    assertThat(repository.status()).isEqualTo("READY");
    assertThat(repository.defaultBranch()).isEqualTo("main");
    assertThat(repository.contentVersion()).isNotBlank();
    assertThat(repository.lastIngestedAt()).isNotNull();

    JobRow job = jobs.findById(ids[1]).orElseThrow();
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    assertThat(job.docsSeen()).isEqualTo(3);
    assertThat(job.chunksWritten()).isPositive();

    assertThat(documents.findByRepository(ids[0])).hasSize(3);
    assertThat(chunks.countByRepository(ids[0])).isPositive();
    assertThat(embedder.callCount()).isPositive();
  }

  @Test
  void reingestSkipsUnchangedDocuments() {
    github.putMarkdown(DocumentKind.README, "README.md", "# Demo\n\nHello", "\"r1\"");
    long[] ids = createRepo();
    ingestionService.ingest(ids[0], ids[1]);
    String version = repositories.findById(ids[0]).orElseThrow().contentVersion();
    int fetches = github.documentFetches();
    long chunkCount = chunks.countByRepository(ids[0]);

    long secondJob = jobs.create(ids[0]);
    ingestionService.ingest(ids[0], secondJob);

    assertThat(github.documentFetches()).isEqualTo(fetches);
    assertThat(repositories.findById(ids[0]).orElseThrow().contentVersion()).isEqualTo(version);
    assertThat(chunks.countByRepository(ids[0])).isEqualTo(chunkCount);
    assertThat(jobs.findById(secondJob).orElseThrow().status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void reingestOnlyRefetchesChangedDocuments() {
    github
        .putMarkdown(DocumentKind.README, "README.md", "# Demo\n\nVersion one", "\"r1\"")
        .putMarkdown(DocumentKind.DOC, "docs/setup.md", "# Setup\n\nStable content", "\"s1\"");
    long[] ids = createRepo();
    ingestionService.ingest(ids[0], ids[1]);
    String version = repositories.findById(ids[0]).orElseThrow().contentVersion();
    int fetches = github.documentFetches();

    github.putMarkdown(DocumentKind.README, "README.md", "# Demo\n\nVersion two changed", "\"r2\"");
    long secondJob = jobs.create(ids[0]);
    ingestionService.ingest(ids[0], secondJob);

    assertThat(github.documentFetches()).isEqualTo(fetches + 1);
    String newVersion = repositories.findById(ids[0]).orElseThrow().contentVersion();
    assertThat(newVersion).isNotEqualTo(version);
  }

  @Test
  void deletesDocumentsRemovedUpstream() {
    github
        .putMarkdown(DocumentKind.README, "README.md", "# Demo", "\"r1\"")
        .putMarkdown(DocumentKind.DOC, "docs/old.md", "# Old\n\nSoon gone", "\"o1\"");
    long[] ids = createRepo();
    ingestionService.ingest(ids[0], ids[1]);
    assertThat(documents.findByRepository(ids[0])).hasSize(2);

    github.removeDocument(DocumentKind.DOC, "docs/old.md");
    long secondJob = jobs.create(ids[0]);
    ingestionService.ingest(ids[0], secondJob);

    assertThat(documents.findByRepository(ids[0]))
        .extracting(DocumentRow::pathOrRef)
        .containsExactly("README.md");
  }

  @Test
  void marksRepositoryAndJobFailedWhenGitHubFails() {
    github.failRepositoryFetch(new GitHubException("GitHub is down"));
    long[] ids = createRepo();

    assertThatThrownBy(() -> ingestionService.ingest(ids[0], ids[1]))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("down");

    assertThat(repositories.findById(ids[0]).orElseThrow().status()).isEqualTo("FAILED");
    JobRow job = jobs.findById(ids[1]).orElseThrow();
    assertThat(job.status()).isEqualTo("FAILED");
    assertThat(job.error()).contains("down");
  }

  @Test
  void titleOnlyIssueStillProducesAChunk() {
    github.issues(
        List.of(
            new DocumentSource(
                DocumentKind.ISSUE,
                "issue-9",
                "https://github.com/acme/demo/issues/9",
                "Only title",
                "",
                null)));
    long[] ids = createRepo();

    ingestionService.ingest(ids[0], ids[1]);

    assertThat(chunks.countByRepository(ids[0])).isPositive();
  }

  private long[] createRepo() {
    long repoId = repositories.insert("acme", "demo");
    long jobId = jobs.create(repoId);
    return new long[] {repoId, jobId};
  }
}
