package com.vishwesh.repoprism.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.vishwesh.repoprism.ingest.DocumentKind;
import com.vishwesh.repoprism.retrieve.FusedHit;
import com.vishwesh.repoprism.retrieve.ScoredChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationVerifierTests {

  private final CitationVerifier verifier = new CitationVerifier();

  @Test
  void keepsValidCitationsAndStripsInvalidMarkers() {
    VerifiedAnswer verified =
        verifier.verify(
            new ParsedAnswer("Install with docker [1]. Also see [99].", List.of(1, 99), false),
            hits(2));

    assertThat(verified.refused()).isFalse();
    assertThat(verified.answer()).isEqualTo("Install with docker [1]. Also see .");
    assertThat(verified.citations()).extracting(Citation::index).containsExactly(1);
  }

  @Test
  void acceptsCitationFieldWithoutMarkers() {
    VerifiedAnswer verified =
        verifier.verify(new ParsedAnswer("Install with docker.", List.of(2), false), hits(2));

    assertThat(verified.refused()).isFalse();
    assertThat(verified.citations()).extracting(Citation::index).containsExactly(2);
  }

  @Test
  void refusesWhenNoCitationIsValid() {
    VerifiedAnswer verified =
        verifier.verify(new ParsedAnswer("Bold claim [99].", List.of(99), false), hits(2));

    assertThat(verified.refused()).isTrue();
    assertThat(verified.answer()).isEqualTo(CitationVerifier.REFUSAL_ANSWER);
    assertThat(verified.citations()).isEmpty();
  }

  @Test
  void refusesWhenNoHitsWereSupplied() {
    VerifiedAnswer verified =
        verifier.verify(new ParsedAnswer("Answer [1]", List.of(1), false), List.of());
    assertThat(verified.refused()).isTrue();
  }

  @Test
  void respectsModelRefusal() {
    VerifiedAnswer verified =
        verifier.verify(new ParsedAnswer("Not enough information.", List.of(), true), hits(2));
    assertThat(verified.refused()).isTrue();
    assertThat(verified.answer()).isEqualTo("Not enough information.");
  }

  @Test
  void blocksSystemPromptLeakage() {
    VerifiedAnswer verified =
        verifier.verify(
            new ParsedAnswer(
                "Sure: " + PromptBuilder.SYSTEM_SENTINEL + " rules are... [1]", List.of(1), false),
            hits(2));

    assertThat(verified.refused()).isTrue();
    assertThat(verified.answer()).isEqualTo(CitationVerifier.REFUSAL_ANSWER);
  }

  @Test
  void streamingPathUsesMarkers() {
    VerifiedAnswer verified = verifier.verifyStreaming("Use docker [2].", hits(2));
    assertThat(verified.refused()).isFalse();
    assertThat(verified.citations()).extracting(Citation::index).containsExactly(2);
  }

  @Test
  void mapsCitationMetadataAndTruncatesSnippet() {
    String longContent = "word ".repeat(100);
    FusedHit hit =
        new FusedHit(
            new ScoredChunk(
                1,
                1,
                DocumentKind.DOC,
                "docs/a.md",
                "https://x",
                "Title",
                "Head",
                longContent,
                1.0),
            1.0,
            1,
            1);

    VerifiedAnswer verified =
        verifier.verify(new ParsedAnswer("Claim [1]", List.of(1), false), List.of(hit));

    Citation citation = verified.citations().getFirst();
    assertThat(citation.title()).isEqualTo("Title");
    assertThat(citation.url()).isEqualTo("https://x");
    assertThat(citation.headingPath()).isEqualTo("Head");
    assertThat(citation.snippet()).endsWith("...");
  }

  private static List<FusedHit> hits(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(id ->
            new FusedHit(
                new ScoredChunk(
                    id,
                    id,
                    DocumentKind.DOC,
                    "docs/" + id + ".md",
                    "https://x/" + id,
                    "t" + id,
                    "h",
                    "content " + id,
                    1.0),
                1.0,
                1,
                null))
        .toList();
  }
}
