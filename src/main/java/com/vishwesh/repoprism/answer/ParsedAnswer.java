package com.vishwesh.repoprism.answer;

import java.util.List;

public record ParsedAnswer(String answer, List<Integer> citations, boolean refused) {}
