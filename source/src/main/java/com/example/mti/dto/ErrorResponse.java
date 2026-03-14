package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
        @JsonProperty("meta") MetaDto meta,
        @JsonProperty("data") ErrorResponseData data
) {
}
