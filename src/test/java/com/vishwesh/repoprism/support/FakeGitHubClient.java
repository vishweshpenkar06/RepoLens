package com.vishwesh.repoprism.support;

import com.vishwesh.repoprism.ingest.DocumentKind;
import com.vishwesh.repoprism.ingest.DocumentRef;
import com.vishwesh.repoprism.ingest.DocumentSource;
import com.vishwesh.repoprism.ingest.FetchedDocument;
import com.vishwesh.repoprism.ingest.GitHubClient;
import com.vishwesh.repoprism.ingest.GitHubException;
import com.vishwesh.repoprism.ingest.GithubUrl;
import com.vishwesh.repoprism.ingest.RepoMetadata;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeGitHubClient implements GitHubClient {

  private final Map<String, DocumentSource> documents = new ConcurrentHashMap<>();
  private final AtomicInteger documentFetches = new AtomicInteger();
  private final AtomicInteger issueFetches = new AtomicInteger();
  private volatile List<DocumentSource> issues = List.of();
  private volatile String branch = "main";
  private volatile RuntimeException repositoryFailure;

  public static String key(DocumentKind kind, String pathOrRef) {
    return kind.name() + "|" + pathOrRef;
  }

  public FakeGitHubClient putDocument(DocumentSource document) {
    documents.put(key(document.kind(), document.pathOrRef()), document);
    return this;
  }

  public FakeGitHubClient putMarkdown(DocumentKind kind, String path, String content, String etag) {
    return putDocument(
        new DocumentSource(
            kind, path, "https://github.com/acme/demo/blob/main/" + path, path, content, etag));
  }

  public FakeGitHubClient removeDocument(DocumentKind kind, String pathOrRef) {
    documents.remove(key(kind, pathOrRef));
    return this;
  }

  public FakeGitHubClient issues(List<DocumentSource> issues) {
    this.issues = issues;
    return this;
  }

  public FakeGitHubClient branch(String branch) {
    this.branch = branch;
    return this;
  }

  public FakeGitHubClient failRepositoryFetch(RuntimeException failure) {
    this.repositoryFailure = failure;
    return this;
  }

  public int documentFetches() {
    return documentFetches.get();
  }

  public int issueFetches() {
    return issueFetches.get();
  }

  public void reset() {
    documents.clear();
    issues = List.of();
    branch = "main";
    repositoryFailure = null;
    documentFetches.set(0);
    issueFetches.set(0);
  }

  @Override
  public RepoMetadata fetchRepository(GithubUrl repo) {
    if (repositoryFailure != null) {
      throw repositoryFailure;
    }
    return new RepoMetadata(repo.owner(), repo.name(), branch, repo.htmlUrl());
  }

  @Override
  public List<DocumentRef> listDocuments(GithubUrl repo, String branch) {
    return documents.values().stream()
        .map(document -> new DocumentRef(document.kind(), document.pathOrRef()))
        .sorted(Comparator.comparing(DocumentRef::path))
        .toList();
  }

  @Override
  public FetchedDocument fetchDocument(
      GithubUrl repo, String branch, DocumentRef ref, String etag) {
    DocumentSource document = documents.get(key(ref.kind(), ref.path()));
    if (document == null) {
      throw new GitHubException("Document not found: " + ref.path());
    }
    if (etag != null && etag.equals(document.etag())) {
      return FetchedDocument.unchanged();
    }
    documentFetches.incrementAndGet();
    return FetchedDocument.modified(document);
  }

  @Override
  public List<DocumentSource> fetchIssues(GithubUrl repo, int limit) {
    issueFetches.incrementAndGet();
    return issues.stream().limit(Math.max(0, limit)).toList();
  }
}
