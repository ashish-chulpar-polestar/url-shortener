package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScoreMetadataDto(
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {}
