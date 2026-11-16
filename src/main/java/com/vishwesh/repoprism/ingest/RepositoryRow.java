package com.vishwesh.repoprism.ingest;

import java.time.Instant;

public record RepositoryRow(
    long id,
    String owner,
    String name,
    String defaultBranch,
    String status,
    Instant lastIngestedAt,
    String contentVersion,
    Instant createdAt) {}
