package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaDto(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("request_timestamp") String requestTimestamp
) {
}
