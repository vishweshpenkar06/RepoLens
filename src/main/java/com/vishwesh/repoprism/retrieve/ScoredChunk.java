package com.vishwesh.repoprism.retrieve;

import com.vishwesh.repoprism.ingest.DocumentKind;

/** A chunk returned by one retriever, ranked best-first within that retriever's result list. */
public record ScoredChunk(
    long chunkId,
    long documentId,
    DocumentKind kind,
    String pathOrRef,
    String url,
    String title,
    String headingPath,
    String content,
    double score) {}
