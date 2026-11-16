package com.vishwesh.repoprism.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTests {

  private final MarkdownChunker chunker = new MarkdownChunker();

  @Test
  void returnsNoChunksForBlankInput() {
    assertThat(chunker.chunk(null)).isEmpty();
    assertThat(chunker.chunk("   \n\n ")).isEmpty();
  }

  @Test
  void keepsHeadingPath() {
    List<Chunk> chunks =
        chunker.chunk(
            """
            # Guide

            Intro text.

            ## Install

            Use docker.

            ### Docker

            Run compose.
            """);

    assertThat(chunks)
        .extracting(Chunk::headingPath)
        .contains("Guide", "Guide > Install", "Guide > Install > Docker");
  }

  @Test
  void handlesSkippedHeadingLevels() {
    List<Chunk> chunks =
        chunker.chunk(
            """
            ### Deep

            deep text

            ## Shallow

            shallow text
            """);

    assertThat(chunks).extracting(Chunk::headingPath).containsExactly("Deep", "Shallow");
  }

  @Test
  void contentBeforeFirstHeadingHasEmptyPath() {
    List<Chunk> chunks = chunker.chunk("Preamble text\n\n# Title\n\nBody");
    assertThat(chunks.getFirst().headingPath()).isEmpty();
    assertThat(chunks.getFirst().content()).isEqualTo("Preamble text");
  }

  @Test
  void ignoresHeadingsInsideCodeFence() {
    List<Chunk> chunks =
        chunker.chunk(
            """
            # Real

            ```bash
            # not a heading
            echo hi
            ```

            body
            """);

    assertThat(chunks).allMatch(chunk -> chunk.headingPath().equals("Real"));
    assertThat(chunks.getFirst().content()).contains("# not a heading");
  }

  @Test
  void stripsBadgesAndHtmlButKeepsGenerics() {
    List<Chunk> chunks =
        chunker.chunk(
            """
            # Docs

            [![Build](https://img.shields.io/badge.svg)](https://ci.example.com)
            <div class="note">Keep this text</div>
            Use List<T> in Java.
            """);

    String content = chunks.getFirst().content();
    assertThat(content)
        .doesNotContain("img.shields.io")
        .doesNotContain("<div")
        .doesNotContain("</div");
    assertThat(content).contains("Keep this text").contains("List<T>");
  }

  @Test
  void keepsSmallTableInOneChunk() {
    List<Chunk> chunks =
        chunker.chunk(
            """
            # Options

            | Flag | Meaning |
            |---|---|
            | a | alpha |
            | b | beta |
            """);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().content()).contains("| a | alpha |", "| b | beta |");
  }

  @Test
  void keepsSmallCodeFenceIntact() {
    String code = "```java\nclass A {\n  void b() {}\n}\n```";
    List<Chunk> chunks = chunker.chunk("# Code\n\n" + code);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().content()).contains("class A {").contains("```");
  }

  @Test
  void splitsLargeSectionsUnderHardMax() {
    StringBuilder body = new StringBuilder("# Long\n\n");
    for (int i = 0; i < 400; i++) {
      body.append("Paragraph ")
          .append(i)
          .append(" with a reasonable amount of text to grow the section steadily.\n\n");
    }
    List<Chunk> chunks = chunker.chunk(body.toString());

    assertThat(chunks.size()).isGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.tokenCount() <= MarkdownChunker.MAX_TOKENS);
    assertThat(chunks.stream().mapToInt(Chunk::tokenCount).sum())
        .isGreaterThan(MarkdownChunker.MAX_TOKENS);
  }

  @Test
  void addsOverlapBetweenSplitChunks() {
    StringBuilder body = new StringBuilder("# Overlap\n\n");
    String sentence =
        "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau.\n\n";
    for (int i = 0; i < 200; i++) {
      body.append(sentence);
    }
    List<Chunk> chunks = chunker.chunk(body.toString());
    assertThat(chunks.size()).isGreaterThan(1);
    assertThat(chunks.get(1).content()).contains("sigma tau");
  }

  @Test
  void splitsOversizedCodeFenceAndWrapsPieces() {
    StringBuilder code = new StringBuilder("# Big code\n\n```java\n");
    for (int i = 0; i < 500; i++) {
      code.append("int value").append(i).append(" = ").append(i).append(";\n");
    }
    code.append("```\n");

    List<Chunk> chunks = chunker.chunk(code.toString());

    assertThat(chunks.size()).isGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.content().startsWith("```java"));
    assertThat(chunks).allMatch(chunk -> chunk.content().endsWith("```"));
    assertThat(chunks).allMatch(chunk -> chunk.tokenCount() <= MarkdownChunker.MAX_TOKENS);
  }

  @Test
  void handlesUnclosedCodeFence() {
    String markdown = "# Code\n\n```java\nint a = 1;\nint b = 2;";
    List<Chunk> chunks = chunker.chunk(markdown);
    assertThat(chunks).isNotEmpty();
    assertThat(chunks.getFirst().content()).contains("int b = 2;");
  }

  @Test
  void estimatesTokensFromCharacters() {
    assertThat(MarkdownChunker.estimateTokens("12345678")).isEqualTo(2);
    assertThat(MarkdownChunker.estimateTokens("")).isZero();
  }

  @Test
  void producesSequentialOrdinals() {
    StringBuilder body = new StringBuilder("# Many\n\n");
    for (int i = 0; i < 300; i++) {
      body.append("Filler paragraph number ").append(i).append(" with enough words to grow.\n\n");
    }
    List<Chunk> chunks = chunker.chunk(body.toString());
    for (int i = 0; i < chunks.size(); i++) {
      assertThat(chunks.get(i).ordinal()).isEqualTo(i);
    }
  }

  @Test
  void splitsOversizedPlainParagraphWithoutBlankLines() {
    String paragraph = "word ".repeat(4000);
    List<Chunk> chunks = chunker.chunk("# Plain\n\n" + paragraph);
    assertThat(chunks.size()).isGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.tokenCount() <= MarkdownChunker.MAX_TOKENS);
  }

  @Test
  void splitsOversizedUnclosedCodeFence() {
    StringBuilder code = new StringBuilder("# Code\n\n```java\n");
    for (int i = 0; i < 600; i++) {
      code.append("long value").append(i).append(" = ").append(i).append("L;\n");
    }
    List<Chunk> chunks = chunker.chunk(code.toString());
    assertThat(chunks.size()).isGreaterThan(1);
    assertThat(chunks).allMatch(chunk -> chunk.content().endsWith("```"));
  }

  @Test
  void overlapsAfterShortChunk() {
    String shortParagraph = "short ".repeat(30).strip();
    String hugeParagraph = "token ".repeat(3000);
    List<Chunk> chunks = chunker.chunk("# Mix\n\n" + shortParagraph + "\n\n" + hugeParagraph);
    assertThat(chunks.size()).isGreaterThan(2);
    assertThat(chunks).allMatch(chunk -> chunk.tokenCount() <= MarkdownChunker.MAX_TOKENS);
  }
}
