package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MtiScoresData(
        @JsonProperty("imo_number") String imoNumber,
        @JsonProperty("year") int year,
        @JsonProperty("month") int month,
        @JsonProperty("scores") ScoresDto scores,
        @JsonProperty("metadata") MtiScoresMetadata metadata
) {
}
