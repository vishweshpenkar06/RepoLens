package com.vishwesh.repolens.api.dto;

import java.time.Instant;

public record JobResponse(
    long id,
    String status,
    Instant startedAt,
    Instant finishedAt,
    int docsSeen,
    int chunksWritten,
    String error) {}
