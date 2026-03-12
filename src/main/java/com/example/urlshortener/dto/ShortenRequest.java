package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShortenRequest(
    @NotBlank(message = "url must not be blank")
    @Size(max = 2048, message = "url must not exceed 2048 characters")
    @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
    String url
) {}
