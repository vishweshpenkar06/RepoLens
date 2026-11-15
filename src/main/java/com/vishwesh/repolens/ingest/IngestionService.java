package com.vishwesh.repolens.ingest;

import com.vishwesh.repolens.common.Hashing;
import com.vishwesh.repolens.config.RepoLensProperties;
import com.vishwesh.repolens.embed.Embedder;
import com.vishwesh.repolens.ingest.store.ChunkStore;
import com.vishwesh.repolens.ingest.store.DocumentStore;
import com.vishwesh.repolens.ingest.store.JobStore;
import com.vishwesh.repolens.ingest.store.RepositoryStore;
import com.vishwesh.repolens.observability.RepoLensMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingests one repository: fetches documents and issues, embeds changed content, and writes chunks
 * transactionally per document. Unchanged documents are skipped using ETags and content hashes.
 */
@Service
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  private final RepositoryStore repositories;
  private final DocumentStore documents;
  private final ChunkStore chunks;
  private final JobStore jobs;
  private final GitHubClient github;
  private final MarkdownChunker chunker;
  private final Embedder embedder;
  private final RepoLensProperties properties;
  private final RepoLensMetrics metrics;
  private final TransactionTemplate transactions;

  public IngestionService(
      RepositoryStore repositories,
      DocumentStore documents,
      ChunkStore chunks,
      JobStore jobs,
      GitHubClient github,
      MarkdownChunker chunker,
      Embedder embedder,
      RepoLensProperties properties,
      RepoLensMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.repositories = repositories;
    this.documents = documents;
    this.chunks = chunks;
    this.jobs = jobs;
    this.github = github;
    this.chunker = chunker;
    this.embedder = embedder;
    this.properties = properties;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void ingest(long repositoryId, long jobId) {
    RepositoryRow repository =
        repositories
            .findById(repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Repository " + repositoryId));
    repositories.updateStatus(repositoryId, "INGESTING");
    long started = System.nanoTime();
    try {
      IngestionResult result = doIngest(repository);
      metrics.recordIngest(
          result.documentsSeen(),
          result.chunksWritten(),
          java.time.Duration.ofNanos(System.nanoTime() - started));
      jobs.succeed(jobId, result.documentsSeen(), result.chunksWritten());
      log.info(
          "Ingested {}: {} documents, {} chunks",
          repository.owner() + "/" + repository.name(),
          result.documentsSeen(),
          result.chunksWritten());
    } catch (RuntimeException e) {
      repositories.markFailed(repositoryId);
      jobs.fail(jobId, e.getMessage());
      throw e;
    }
  }

  private IngestionResult doIngest(RepositoryRow repository) {
    GithubUrl url = new GithubUrl(repository.owner(), repository.name());
    RepoMetadata metadata = github.fetchRepository(url);
    String branch = metadata.defaultBranch();

    Map<String, DocumentRow> existing =
        documents.findByRepository(repository.id()).stream()
            .collect(Collectors.toMap(DocumentRow::key, row -> row));

    Map<String, String> hashes = new HashMap<>();
    Set<String> seen = new HashSet<>();
    List<DocumentSource> changed = new ArrayList<>();

    for (DocumentRef ref : github.listDocuments(url, branch)) {
      String key = DocumentRow.key(ref.kind(), ref.path());
      seen.add(key);
      DocumentRow row = existing.get(key);
      FetchedDocument fetched =
          github.fetchDocument(url, branch, ref, row == null ? null : row.etag());
      if (fetched.notModified() && row != null) {
        hashes.put(key, row.contentHash());
      } else {
        changed.add(fetched.document());
      }
    }

    if (properties.github().issuesEnabled()) {
      for (DocumentSource issue : github.fetchIssues(url, properties.github().issueLimit())) {
        String key = DocumentRow.key(issue.kind(), issue.pathOrRef());
        seen.add(key);
        String hash = Hashing.sha256(nullToEmpty(issue.content()));
        DocumentRow row = existing.get(key);
        if (row != null && hash.equals(row.contentHash())) {
          hashes.put(key, hash);
        } else {
          changed.add(issue);
        }
      }
    }

    List<WriteResult> written = writeChanged(repository.id(), changed);
    written.forEach(result -> hashes.put(result.key(), result.hash()));

    List<Long> removed =
        existing.values().stream()
            .filter(row -> !seen.contains(row.key()))
            .map(DocumentRow::id)
            .toList();
    documents.deleteByIds(removed);

    String contentVersion =
        Hashing.sha256(
            hashes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n")));

    repositories.markReady(repository.id(), branch, contentVersion);
    int chunksWritten = written.stream().mapToInt(WriteResult::chunks).sum();
    return new IngestionResult(seen.size(), chunksWritten);
  }

  private List<WriteResult> writeChanged(long repositoryId, List<DocumentSource> sources) {
    if (sources.isEmpty()) {
      return List.of();
    }
    Semaphore embeddingPermits = new Semaphore(Math.max(1, properties.embedding().concurrency()));
    Semaphore documentPermits =
        new Semaphore(Math.max(2, properties.embedding().concurrency() * 2));

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<WriteResult>> tasks =
          sources.stream()
              .<Callable<WriteResult>>map(source ->
                  () -> {
                    documentPermits.acquire();
                    try {
                      return writeOne(repositoryId, source, embeddingPermits);
                    } finally {
                      documentPermits.release();
                    }
                  })
              .toList();
      List<Future<WriteResult>> futures = executor.invokeAll(tasks);
      List<WriteResult> results = new ArrayList<>(futures.size());
      for (Future<WriteResult> future : futures) {
        try {
          results.add(future.get());
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while writing documents", e);
        }
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while ingesting", e);
    }
  }

  private WriteResult writeOne(
      long repositoryId, DocumentSource source, Semaphore embeddingPermits) {
    String content = nullToEmpty(source.content());
    String hash = Hashing.sha256(content);
    List<Chunk> documentChunks = chunker.chunk(content);
    if (documentChunks.isEmpty() && source.title() != null && !source.title().isBlank()) {
      String title = source.title().strip();
      documentChunks = List.of(new Chunk(0, null, title, MarkdownChunker.estimateTokens(title)));
    }

    List<float[]> embeddings = new ArrayList<>(documentChunks.size());
    int batchSize = Math.max(1, properties.embedding().batchSize());
    for (int from = 0; from < documentChunks.size(); from += batchSize) {
      List<Chunk> batch =
          documentChunks.subList(from, Math.min(from + batchSize, documentChunks.size()));
      List<String> texts = batch.stream().map(IngestionService::embeddingText).toList();
      try {
        embeddingPermits.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the embedder", e);
      }
      try {
        embeddings.addAll(embedder.embedAll(texts));
      } finally {
        embeddingPermits.release();
      }
    }
    if (embeddings.size() != documentChunks.size()) {
      throw new IllegalStateException("Embedding count mismatch for " + source.pathOrRef());
    }

    List<Chunk> chunksToWrite = documentChunks;
    transactions.executeWithoutResult(status -> {
      long documentId = documents.upsert(repositoryId, source, hash);
      chunks.deleteByDocument(documentId);
      chunks.insertAll(documentId, repositoryId, chunksToWrite, embeddings);
    });

    String key = DocumentRow.key(source.kind(), source.pathOrRef());
    return new WriteResult(key, hash, documentChunks.size());
  }

  private static String embeddingText(Chunk chunk) {
    if (chunk.headingPath() == null || chunk.headingPath().isBlank()) {
      return chunk.content();
    }
    return chunk.headingPath() + "\n" + chunk.content();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record WriteResult(String key, String hash, int chunks) {}

  private record IngestionResult(int documentsSeen, int chunksWritten) {}
}
