package com.vishwesh.repoprism.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AnswerParserTests {

  private final AnswerParser parser = new AnswerParser(JsonMapper.builder().build());

  @Test
  void parsesPlainJson() {
    ParsedAnswer parsed =
        parser.parse("{\"answer\":\"Use docker [1].\",\"citations\":[1],\"refused\":false}");

    assertThat(parsed.answer()).isEqualTo("Use docker [1].");
    assertThat(parsed.citations()).containsExactly(1);
    assertThat(parsed.refused()).isFalse();
  }

  @Test
  void stripsMarkdownFences() {
    ParsedAnswer parsed =
        parser.parse("```json\n{\"answer\":\"A [2]\",\"citations\":[2],\"refused\":false}\n```");
    assertThat(parsed.answer()).isEqualTo("A [2]");
    assertThat(parsed.citations()).containsExactly(2);
  }

  @Test
  void extractsJsonFromSurroundingProse() {
    ParsedAnswer parsed =
        parser.parse("Sure! {\"answer\":\"A [1]\",\"citations\":[1],\"refused\":false} done");
    assertThat(parsed.answer()).isEqualTo("A [1]");
  }

  @Test
  void fallsBackToRawTextWhenNotJson() {
    ParsedAnswer parsed = parser.parse("Just some text without JSON");
    assertThat(parsed.answer()).isEqualTo("Just some text without JSON");
    assertThat(parsed.citations()).isEmpty();
    assertThat(parsed.refused()).isFalse();
  }

  @Test
  void ignoresNonIntegerCitations() {
    ParsedAnswer parsed =
        parser.parse("{\"answer\":\"A\",\"citations\":[1,\"two\",3],\"refused\":false}");
    assertThat(parsed.citations()).containsExactly(1, 3);
  }

  @Test
  void keepsEmptyAnswerEmptyInsteadOfReturningRawJson() {
    ParsedAnswer parsed = parser.parse("{\"answer\":\"\",\"citations\":[],\"refused\":true}");

    assertThat(parsed.answer()).isEmpty();
    assertThat(parsed.refused()).isTrue();
  }

  @Test
  void treatsBlankOutputAsRefusal() {
    ParsedAnswer parsed = parser.parse("   ");
    assertThat(parsed.refused()).isTrue();
    assertThat(parsed.answer()).isEmpty();
  }
}
