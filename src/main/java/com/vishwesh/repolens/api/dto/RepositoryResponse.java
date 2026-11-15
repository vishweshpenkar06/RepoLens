package com.vishwesh.repolens.api.dto;

import java.time.Instant;

public record RepositoryResponse(
    long id,
    String owner,
    String name,
    String slug,
    String status,
    String defaultBranch,
    Instant lastIngestedAt,
    String contentVersion,
    Instant createdAt,
    JobResponse latestJob) {}
