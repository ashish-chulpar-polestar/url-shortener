package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

public record ShortenRequest(
        @NotBlank(message = "Field 'url' is required and must not be empty.")
        String url
) {}
