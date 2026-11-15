package com.vishwesh.repolens.ingest;

public record Chunk(int ordinal, String headingPath, String content, int tokenCount) {}
