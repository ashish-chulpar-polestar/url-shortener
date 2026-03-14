package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponseData(
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("title") String title,
        @JsonProperty("message") String message
) {
}
