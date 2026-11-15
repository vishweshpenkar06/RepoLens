package com.vishwesh.repolens.answer;

import java.util.List;

public record CachedAnswer(String answer, List<Citation> citations, boolean refused) {}
