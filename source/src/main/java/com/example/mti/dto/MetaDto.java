package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MetaDto {

    @JsonProperty("request_id")
    private final String requestId;

    @JsonProperty("request_timestamp")
    private final String requestTimestamp;

    public MetaDto(String requestId, String requestTimestamp) {
        this.requestId = requestId;
        this.requestTimestamp = requestTimestamp;
    }

    public String getRequestId() { return requestId; }
    public String getRequestTimestamp() { return requestTimestamp; }
}
