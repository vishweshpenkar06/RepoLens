package com.vishwesh.repoprism.ingest;

public record FetchedDocument(boolean notModified, DocumentSource document) {

  public static FetchedDocument unchanged() {
    return new FetchedDocument(true, null);
  }

  public static FetchedDocument modified(DocumentSource document) {
    return new FetchedDocument(false, document);
  }
}
