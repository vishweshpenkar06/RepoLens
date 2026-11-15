package com.vishwesh.repolens.api.dto;

import com.vishwesh.repolens.answer.AnswerResult;
import java.util.List;

public record AskResponse(
    String answer,
    List<CitationResponse> citations,
    boolean refused,
    long latencyMs,
    boolean cached) {

  public static AskResponse from(AnswerResult result) {
    return new AskResponse(
        result.answer(),
        result.citations().stream().map(CitationResponse::from).toList(),
        result.refused(),
        result.latencyMs(),
        result.cached());
  }
}
