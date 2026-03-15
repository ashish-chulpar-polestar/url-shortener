package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ErrorDataDto {

    @JsonProperty("error_code")
    private final String errorCode;

    private final String title;
    private final String message;

    public ErrorDataDto(String errorCode, String title, String message) {
        this.errorCode = errorCode;
        this.title = title;
        this.message = message;
    }

    public String getErrorCode() { return errorCode; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
}
