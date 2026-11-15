package com.vishwesh.repolens.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(@NotBlank @Size(max = 1000) String question, Integer topK) {}
