package com.vishwesh.repolens.answer;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface LlmGateway {

  /** Returns the raw model output (expected to be JSON for {@code complete}). */
  String complete(LlmRequest request);

  /** Streams answer tokens. The default implementation splits the complete output into words. */
  default void stream(LlmRequest request, Consumer<String> onToken) {
    String text = complete(request);
    Matcher matcher = Pattern.compile("\\S+\\s*").matcher(text);
    while (matcher.find()) {
      onToken.accept(matcher.group());
    }
  }

  String providerName();
}
