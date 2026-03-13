package com.polestar.mti.constant;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ERR_101("Resource Not Found", HttpStatus.NOT_FOUND),
    ERR_102("Invalid Parameters", HttpStatus.BAD_REQUEST),
    ERR_103("Invalid IMO Format", HttpStatus.BAD_REQUEST),
    ERR_104("Invalid Date Range", HttpStatus.BAD_REQUEST),
    ERR_105("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String title;
    private final HttpStatus httpStatus;

    ErrorCode(String title, HttpStatus httpStatus) {
        this.title = title;
        this.httpStatus = httpStatus;
    }

    public String getTitle() {
        return title;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return this.name();
    }
}
