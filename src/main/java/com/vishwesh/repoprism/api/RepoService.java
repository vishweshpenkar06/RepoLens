package com.vishwesh.repoprism.api;

import com.vishwesh.repoprism.api.dto.JobResponse;
import com.vishwesh.repoprism.api.dto.RepositoryResponse;
import com.vishwesh.repoprism.ingest.GithubUrl;
import com.vishwesh.repoprism.ingest.IngestionJobRunner;
import com.vishwesh.repoprism.ingest.JobRow;
import com.vishwesh.repoprism.ingest.RepositoryRow;
import com.vishwesh.repoprism.ingest.SsrfGuard;
import com.vishwesh.repoprism.ingest.store.JobStore;
import com.vishwesh.repoprism.ingest.store.RepositoryStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RepoService {

  private final RepositoryStore repositories;
  private final JobStore jobs;
  private final SsrfGuard ssrfGuard;
  private final IngestionJobRunner jobRunner;
  private final TransactionTemplate transactions;

  public RepoService(
      RepositoryStore repositories,
      JobStore jobs,
      SsrfGuard ssrfGuard,
      IngestionJobRunner jobRunner,
      PlatformTransactionManager transactionManager) {
    this.repositories = repositories;
    this.jobs = jobs;
    this.ssrfGuard = ssrfGuard;
    this.jobRunner = jobRunner;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public CreatedRepo create(String rawUrl) {
    GithubUrl url = ssrfGuard.validate(rawUrl);
    if (repositories.findByOwnerAndName(url.owner(), url.name()).isPresent()) {
      throw new DuplicateRepositoryException(url.slug());
    }
    long[] ids = new long[2];
    transactions.executeWithoutResult(status -> {
      ids[0] = repositories.insert(url.owner(), url.name());
      ids[1] = jobs.create(ids[0]);
    });
    start(ids[0], ids[1]);
    return new CreatedRepo(ids[0], ids[1]);
  }

  public CreatedRepo reindex(long repositoryId) {
    RepositoryRow repository =
        repositories
            .findById(repositoryId)
            .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    if (jobRunner.isRunning(repositoryId)) {
      throw new IngestionConflictException("An ingestion is already running for this repository");
    }
    long jobId = jobs.create(repositoryId);
    repositories.updateStatus(repositoryId, "PENDING");
    start(repositoryId, jobId);
    return new CreatedRepo(repositoryId, jobId);
  }

  public RepositoryResponse find(long repositoryId) {
    RepositoryRow repository =
        repositories
            .findById(repositoryId)
            .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    return toResponse(repository);
  }

  public List<RepositoryResponse> list() {
    return repositories.findAll().stream().map(this::toResponse).toList();
  }

  private void start(long repositoryId, long jobId) {
    try {
      jobRunner.submit(repositoryId, jobId);
    } catch (RuntimeException e) {
      jobs.fail(jobId, e.getMessage());
      throw new IngestionConflictException(e.getMessage());
    }
  }

  private RepositoryResponse toResponse(RepositoryRow row) {
    JobResponse latestJob = jobs.latestForRepository(row.id()).map(RepoService::toJob).orElse(null);
    return new RepositoryResponse(
        row.id(),
        row.owner(),
        row.name(),
        row.owner() + "/" + row.name(),
        row.status(),
        row.defaultBranch(),
        row.lastIngestedAt(),
        row.contentVersion(),
        row.createdAt(),
        latestJob);
  }

  private static JobResponse toJob(JobRow job) {
    return new JobResponse(
        job.id(),
        job.status(),
        job.startedAt(),
        job.finishedAt(),
        job.docsSeen(),
        job.chunksWritten(),
        job.error());
  }

  public record CreatedRepo(long repoId, long jobId) {}
}
