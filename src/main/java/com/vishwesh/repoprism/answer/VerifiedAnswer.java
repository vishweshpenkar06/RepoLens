package com.vishwesh.repoprism.answer;

import java.util.List;

public record VerifiedAnswer(String answer, List<Citation> citations, boolean refused) {}
