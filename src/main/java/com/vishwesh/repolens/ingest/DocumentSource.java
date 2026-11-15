package com.vishwesh.repolens.ingest;

public record DocumentSource(
    DocumentKind kind, String pathOrRef, String url, String title, String content, String etag) {}
