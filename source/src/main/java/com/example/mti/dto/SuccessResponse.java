package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SuccessResponse(
        @JsonProperty("meta") MetaDto meta,
        @JsonProperty("data") MtiScoresData data
) {
}
