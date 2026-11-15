package com.vishwesh.repolens.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRepoRequest(@NotBlank @Size(max = 300) String url) {}
