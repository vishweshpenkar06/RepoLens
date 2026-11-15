package com.vishwesh.repolens.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits markdown into chunks of roughly {@value #TARGET_TOKENS} tokens (hard max {@value
 * #MAX_TOKENS}), preserving heading paths, tables and fenced code blocks where possible.
 *
 * <p>Token counts are estimates: {@code ceil(characters / 4)}, which is close enough for the MiniLM
 * tokenizer and keeps chunking free of a tokenizer dependency (ADR-0010).
 */
@Component
public class MarkdownChunker {

  public static final int TARGET_TOKENS = 500;
  public static final int MAX_TOKENS = 800;
  static final int OVERLAP_PERCENT = 12;

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
  private static final Pattern BADGE = Pattern.compile("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)");
  private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->");
  private static final Pattern HTML_TAG =
      Pattern.compile(
          "</?(?:div|span|p|br|img|a|table|thead|tbody|tr|td|th|pre|code|details|summary|kbd|sub|sup|b|i|strong|em|hr|ul|ol|li|blockquote)(?:\\s[^>]*)?/?>",
          Pattern.CASE_INSENSITIVE);

  public List<Chunk> chunk(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    List<Section> sections = splitIntoSections(clean(markdown));
    List<Chunk> chunks = new ArrayList<>();
    int ordinal = 0;
    for (Section section : sections) {
      for (String piece : splitSection(section.content())) {
        String content = piece.strip();
        if (content.isEmpty()) {
          continue;
        }
        chunks.add(new Chunk(ordinal++, section.headingPath(), content, estimateTokens(content)));
      }
    }
    return chunks;
  }

  public static int estimateTokens(String text) {
    return (int) Math.ceil(text.length() / 4.0);
  }

  private static String clean(String markdown) {
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    StringBuilder out = new StringBuilder(markdown.length());
    boolean inFence = false;
    String fenceMarker = null;
    for (String line : lines) {
      Matcher fence = FENCE.matcher(line);
      if (fence.matches()) {
        String marker = fence.group(1);
        if (!inFence) {
          inFence = true;
          fenceMarker = marker;
        } else if (marker.charAt(0) == fenceMarker.charAt(0)
            && marker.length() >= fenceMarker.length()) {
          inFence = false;
          fenceMarker = null;
        }
        out.append(line).append('\n');
        continue;
      }
      if (inFence) {
        out.append(line).append('\n');
      } else {
        out.append(cleanLine(line)).append('\n');
      }
    }
    return out.toString().replaceAll("\n{3,}", "\n\n");
  }

  private static String cleanLine(String line) {
    String cleaned = BADGE.matcher(line).replaceAll("");
    cleaned = HTML_COMMENT.matcher(cleaned).replaceAll("");
    cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
    return cleaned.stripTrailing();
  }

  private List<Section> splitIntoSections(String markdown) {
    List<Section> sections = new ArrayList<>();
    Deque<Heading> headings = new ArrayDeque<>();
    List<String> current = new ArrayList<>();
    String[] lines = markdown.split("\n", -1);
    boolean inFence = false;
    String fenceMarker = null;

    for (String line : lines) {
      Matcher fence = FENCE.matcher(line);
      if (fence.matches()) {
        String marker = fence.group(1);
        if (!inFence) {
          inFence = true;
          fenceMarker = marker;
        } else if (marker.charAt(0) == fenceMarker.charAt(0)
            && marker.length() >= fenceMarker.length()) {
          inFence = false;
          fenceMarker = null;
        }
        current.add(line);
        continue;
      }
      Matcher heading = inFence ? null : HEADING.matcher(line);
      if (heading != null && heading.matches()) {
        flush(sections, headings, current);
        int level = heading.group(1).length();
        while (!headings.isEmpty() && headings.peekLast().level() >= level) {
          headings.removeLast();
        }
        headings.addLast(new Heading(heading.group(2).strip(), level));
        continue;
      }
      current.add(line);
    }
    flush(sections, headings, current);
    return sections;
  }

  private static void flush(List<Section> sections, Deque<Heading> headings, List<String> lines) {
    String content = String.join("\n", lines).strip();
    if (!content.isBlank()) {
      String path =
          headings.stream().map(Heading::title).collect(java.util.stream.Collectors.joining(" > "));
      sections.add(new Section(path, content));
    }
    lines.clear();
  }

  record Heading(String title, int level) {}

  private List<String> splitSection(String content) {
    List<String> units = splitIntoUnits(content);
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    String overlap = "";
    for (String unit : units) {
      List<String> pieces =
          estimateTokens(unit) > MAX_TOKENS ? splitOversized(unit) : List.of(unit);
      for (String piece : pieces) {
        int pieceTokens = estimateTokens(piece);
        if (current.length() > 0
            && estimateTokens(current.toString()) + pieceTokens > TARGET_TOKENS) {
          String flushed = current.toString().strip();
          chunks.add(flushed);
          overlap = tail(flushed);
          current.setLength(0);
        }
        if (current.length() == 0
            && !overlap.isBlank()
            && !piece.stripLeading().startsWith("```")
            && !piece.stripLeading().startsWith("~~~")
            && estimateTokens(overlap) + pieceTokens <= MAX_TOKENS) {
          current.append(overlap).append("\n\n");
        }
        if (current.length() > 0) {
          current.append("\n\n");
        }
        current.append(piece);
      }
    }
    if (!current.isEmpty()) {
      String flushed = current.toString().strip();
      if (!flushed.isBlank()) {
        chunks.add(flushed);
      }
    }
    return chunks;
  }

  private List<String> splitIntoUnits(String content) {
    List<String> units = new ArrayList<>();
    List<String> buffer = new ArrayList<>();
    String[] lines = content.split("\n", -1);
    boolean inFence = false;
    String fenceMarker = null;
    boolean inTable = false;

    for (String line : lines) {
      Matcher fence = FENCE.matcher(line);
      if (fence.matches()) {
        String marker = fence.group(1);
        if (!inFence) {
          addUnit(units, buffer);
          inFence = true;
          fenceMarker = marker;
        } else if (marker.charAt(0) == fenceMarker.charAt(0)
            && marker.length() >= fenceMarker.length()) {
          inFence = false;
          fenceMarker = null;
          buffer.add(line);
          addUnit(units, buffer);
          continue;
        }
        buffer.add(line);
        continue;
      }

      if (inFence) {
        buffer.add(line);
        continue;
      }

      boolean tableLine = line.stripLeading().startsWith("|");
      if (tableLine != inTable) {
        addUnit(units, buffer);
        inTable = tableLine;
      }

      if (line.isBlank()) {
        addUnit(units, buffer);
      } else {
        buffer.add(line);
      }
    }
    addUnit(units, buffer);
    return units;
  }

  private static void addUnit(List<String> units, List<String> buffer) {
    String unit = String.join("\n", buffer).strip();
    if (!unit.isBlank()) {
      units.add(unit);
    }
    buffer.clear();
  }

  private List<String> splitOversized(String unit) {
    Matcher fence = FENCE.matcher(unit.lines().findFirst().orElse(""));
    if (fence.matches()) {
      return splitFence(unit, fence.group(1), fence.group(2));
    }
    return splitByWords(unit);
  }

  private List<String> splitFence(String unit, String marker, String language) {
    String[] lines = unit.split("\n", -1);
    String prefix = marker + language + "\n";
    String suffix = "\n" + marker;
    int budget = Math.max(50, TARGET_TOKENS - estimateTokens(prefix + suffix));
    int end = lines.length;
    if (end > 1) {
      String last = lines[end - 1].strip();
      if (last.startsWith(marker) && last.chars().allMatch(c -> c == marker.charAt(0))) {
        end--;
      }
    }
    List<String> pieces = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    for (int i = 1; i < end; i++) {
      String line = lines[i];
      if (estimateTokens(body.toString()) + estimateTokens(line) + 1 > budget
          && body.length() > 0) {
        pieces.add(prefix + body.toString().stripTrailing() + suffix);
        body.setLength(0);
      }
      body.append(line).append('\n');
    }
    if (!body.isEmpty()) {
      pieces.add(prefix + body.toString().stripTrailing() + suffix);
    }
    return pieces;
  }

  private List<String> splitByWords(String unit) {
    String[] words = unit.split("\\s+");
    int maxWords = Math.max(25, TARGET_TOKENS);
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int count = 0;
    for (String word : words) {
      int wordsInWord = Math.max(1, estimateTokens(word));
      if (count > 0 && count + wordsInWord > maxWords) {
        pieces.add(current.toString().strip());
        current.setLength(0);
        count = 0;
      }
      if (current.length() > 0) {
        current.append(' ');
      }
      current.append(word);
      count += wordsInWord;
    }
    if (!current.isEmpty()) {
      pieces.add(current.toString().strip());
    }
    return pieces;
  }

  private static String tail(String content) {
    int targetChars = (TARGET_TOKENS * 4 * OVERLAP_PERCENT) / 100;
    if (content.length() <= targetChars) {
      return content;
    }
    int start = content.length() - targetChars;
    int space = content.indexOf(' ', start);
    if (space < 0 || space + 1 >= content.length()) {
      return content.substring(content.length() - targetChars).strip();
    }
    return content.substring(space + 1).strip();
  }

  record Section(String headingPath, String content) {}
}
