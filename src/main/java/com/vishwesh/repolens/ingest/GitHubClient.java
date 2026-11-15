package com.vishwesh.repolens.ingest;

import java.util.List;

public interface GitHubClient {

  RepoMetadata fetchRepository(GithubUrl repo);

  List<DocumentRef> listDocuments(GithubUrl repo, String branch);

  /**
   * Fetches one markdown document. When {@code etag} is non-null it is sent as {@code
   * If-None-Match} and an unchanged document is reported via {@link FetchedDocument#unchanged()}.
   */
  FetchedDocument fetchDocument(GithubUrl repo, String branch, DocumentRef ref, String etag);

  List<DocumentSource> fetchIssues(GithubUrl repo, int limit);
}
