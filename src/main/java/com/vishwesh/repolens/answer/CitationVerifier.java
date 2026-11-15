package com.vishwesh.repolens.answer;

import com.vishwesh.repolens.retrieve.FusedHit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates citations against the supplied context. Invalid indices are stripped from the answer,
 * and an answer with zero valid citations is converted into a refusal.
 */
@Component
public class CitationVerifier {

  static final String REFUSAL_ANSWER = "I don't know based on the provided context.";
  private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

  public VerifiedAnswer verify(ParsedAnswer parsed, List<FusedHit> hits) {
    if (hits == null || hits.isEmpty()) {
      return refusal();
    }
    if (parsed.refused()) {
      return new VerifiedAnswer(
          parsed.answer().isBlank() ? REFUSAL_ANSWER : parsed.answer(), List.of(), true);
    }
    if (parsed.answer().contains(PromptBuilder.SYSTEM_SENTINEL)) {
      return refusal();
    }

    int max = hits.size();
    Set<Integer> valid = new TreeSet<>();
    for (int index : parsed.citations()) {
      if (index >= 1 && index <= max) {
        valid.add(index);
      }
    }

    Matcher matcher = MARKER.matcher(parsed.answer());
    StringBuilder cleaned = new StringBuilder();
    while (matcher.find()) {
      int index = Integer.parseInt(matcher.group(1));
      if (index >= 1 && index <= max) {
        valid.add(index);
        matcher.appendReplacement(cleaned, "[" + index + "]");
      } else {
        matcher.appendReplacement(cleaned, "");
      }
    }
    matcher.appendTail(cleaned);
    String answer = cleaned.toString().replaceAll("[ \\t]{2,}", " ").strip();

    if (answer.isBlank() || valid.isEmpty()) {
      return refusal();
    }
    List<Citation> citations =
        valid.stream().map(index -> toCitation(index, hits.get(index - 1))).toList();
    return new VerifiedAnswer(answer, citations, false);
  }

  public VerifiedAnswer verifyStreaming(String answer, List<FusedHit> hits) {
    return verify(new ParsedAnswer(answer == null ? "" : answer, List.of(), false), hits);
  }

  public VerifiedAnswer refusal() {
    return new VerifiedAnswer(REFUSAL_ANSWER, List.of(), true);
  }

  private Citation toCitation(int index, FusedHit hit) {
    return new Citation(
        index,
        hit.chunk().title(),
        hit.chunk().url(),
        hit.chunk().headingPath(),
        snippet(hit.chunk().content()));
  }

  private static String snippet(String content) {
    String normalized = content == null ? "" : content.replaceAll("\\s+", " ").strip();
    int max = 300;
    return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
  }
}
