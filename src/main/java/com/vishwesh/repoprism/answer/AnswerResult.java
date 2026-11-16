package com.vishwesh.repoprism.answer;

import java.util.List;

public record AnswerResult(
    String answer, List<Citation> citations, boolean refused, long latencyMs, boolean cached) {}
