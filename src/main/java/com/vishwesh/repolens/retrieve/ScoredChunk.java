package com.vishwesh.repolens.retrieve;

import com.vishwesh.repolens.ingest.DocumentKind;

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
