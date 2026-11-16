package com.vishwesh.repoprism.api.dto;

import com.vishwesh.repoprism.answer.Citation;

public record CitationResponse(
    int index, String title, String url, String headingPath, String snippet) {

  public static CitationResponse from(Citation citation) {
    return new CitationResponse(
        citation.index(),
        citation.title(),
        citation.url(),
        citation.headingPath(),
        citation.snippet());
  }
}
