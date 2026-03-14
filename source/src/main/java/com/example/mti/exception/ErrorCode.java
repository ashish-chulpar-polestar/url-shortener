package com.example.mti.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    ERR_101("ERR_101", "Resource Not Found", HttpStatus.NOT_FOUND),
    ERR_102("ERR_102", "Invalid Parameters", HttpStatus.BAD_REQUEST),
    ERR_103("ERR_103", "Invalid IMO Format", HttpStatus.BAD_REQUEST),
    ERR_104("ERR_104", "Invalid Date Range", HttpStatus.BAD_REQUEST),
    ERR_105("ERR_105", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String title;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String title, HttpStatus httpStatus) {
        this.code = code;
        this.title = title;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
