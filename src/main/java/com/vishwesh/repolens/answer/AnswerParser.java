package com.vishwesh.repolens.answer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses the model's structured output, tolerating fences and surrounding prose. */
@Component
public class AnswerParser {

  private final JsonMapper objectMapper;

  public AnswerParser(JsonMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ParsedAnswer parse(String raw) {
    String text = raw == null ? "" : raw.strip();
    if (text.isEmpty()) {
      return new ParsedAnswer("", List.of(), true);
    }
    String candidate = stripFences(text);
    JsonNode json = tryParse(candidate);
    if (json == null) {
      int start = candidate.indexOf('{');
      int end = candidate.lastIndexOf('}');
      if (start >= 0 && end > start) {
        json = tryParse(candidate.substring(start, end + 1));
      }
    }
    if (json == null || !json.isObject()) {
      return new ParsedAnswer(text, List.of(), false);
    }
    String answer = json.path("answer").asText(text).strip();
    boolean refused = json.path("refused").asBoolean(false);
    List<Integer> citations = new ArrayList<>();
    JsonNode citationsNode = json.path("citations");
    if (citationsNode.isArray()) {
      for (JsonNode citation : citationsNode) {
        if (citation.canConvertToInt()) {
          citations.add(citation.asInt());
        }
      }
    }
    return new ParsedAnswer(answer.isEmpty() ? text : answer, citations, refused);
  }

  private JsonNode tryParse(String text) {
    try {
      return objectMapper.readTree(text);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String stripFences(String text) {
    String result = text;
    if (result.startsWith("```")) {
      int firstNewline = result.indexOf('\n');
      if (firstNewline >= 0) {
        result = result.substring(firstNewline + 1);
      }
      int closing = result.lastIndexOf("```");
      if (closing >= 0) {
        result = result.substring(0, closing);
      }
    }
    return result.strip();
  }
}
