package com.vishwesh.repoprism.ingest;

import java.time.Instant;

public record JobRow(
    long id,
    long repositoryId,
    String status,
    Instant startedAt,
    Instant finishedAt,
    int docsSeen,
    int chunksWritten,
    String error) {}
