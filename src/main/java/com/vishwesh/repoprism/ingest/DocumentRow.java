package com.vishwesh.repoprism.ingest;

public record DocumentRow(
    long id,
    long repositoryId,
    DocumentKind kind,
    String pathOrRef,
    String url,
    String title,
    String contentHash,
    String etag,
    String content) {

  public String key() {
    return key(kind, pathOrRef);
  }

  public static String key(DocumentKind kind, String pathOrRef) {
    return kind.name() + "|" + pathOrRef;
  }
}
