package com.vishwesh.repoprism.ingest;

public record Chunk(int ordinal, String headingPath, String content, int tokenCount) {}
