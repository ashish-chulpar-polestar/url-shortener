package com.polestar.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class MetaDto {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("request_timestamp")
    private String requestTimestamp;

    public static MetaDto of(String requestId) {
        return new MetaDto(requestId, Instant.now().toString());
    }
}
