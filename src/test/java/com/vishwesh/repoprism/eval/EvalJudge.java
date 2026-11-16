package com.vishwesh.repoprism.eval;

import com.vishwesh.repoprism.answer.LlmGateway;
import com.vishwesh.repoprism.answer.LlmRequest;
import com.vishwesh.repoprism.retrieve.FusedHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLM-as-a-judge for groundedness. Uses the committed prompt in {@code eval/judge-prompt.md}. */
final class EvalJudge {

  record Judgement(boolean grounded, int score, String reason) {}

  private final LlmGateway gateway;
  private final JsonMapper mapper;
  private final String judgePrompt;

  EvalJudge(LlmGateway gateway, JsonMapper mapper) throws IOException {
    this.gateway = gateway;
    this.mapper = mapper;
    this.judgePrompt = Files.readString(Path.of("eval", "judge-prompt.md"));
  }

  Optional<Judgement> judge(String question, String answer, List<FusedHit> hits) {
    StringBuilder user = new StringBuilder();
    user.append("QUESTION:\n").append(question).append("\n\n");
    user.append("CONTEXT:\n<<<BEGIN_UNTRUSTED_CONTEXT>>>\n");
    for (int i = 0; i < hits.size(); i++) {
      user.append('[')
          .append(i + 1)
          .append("] ")
          .append(hits.get(i).chunk().content())
          .append("\n\n");
    }
    user.append("<<<END_UNTRUSTED_CONTEXT>>>\n\n");
    user.append("ANSWER:\n").append(answer).append('\n');
    try {
      String raw = gateway.complete(new LlmRequest(judgePrompt, user.toString()));
      JsonNode json = extractJson(raw);
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Judgement(
              json.path("grounded").asBoolean(false),
              json.path("score").asInt(0),
              json.path("reason").asText("")));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private JsonNode extractJson(String raw) {
    if (raw == null) {
      return null;
    }
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      return mapper.readTree(raw.substring(start, end + 1));
    } catch (RuntimeException e) {
      return null;
    }
  }
}
