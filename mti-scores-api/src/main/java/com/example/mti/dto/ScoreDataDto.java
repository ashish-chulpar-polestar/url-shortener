package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScoreDataDto(
        @JsonProperty("imo_number") String imoNumber,
        Integer year,
        Integer month,
        ScoresDto scores,
        ScoreMetadataDto metadata
) {}
