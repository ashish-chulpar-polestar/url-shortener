package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorDataDto(
        @JsonProperty("error_code") String errorCode,
        String title,
        String message
) {}
