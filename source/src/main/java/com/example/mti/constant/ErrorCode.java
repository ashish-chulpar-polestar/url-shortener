package com.example.mti.constant;

public enum ErrorCode {

    ERR_101("ERR_101", "Resource Not Found", 404),
    ERR_102("ERR_102", "Invalid Parameters", 400),
    ERR_103("ERR_103", "Invalid IMO Format", 400),
    ERR_104("ERR_104", "Invalid Date Range", 400),
    ERR_105("ERR_105", "Internal Server Error", 500);

    private final String code;
    private final String title;
    private final int httpStatus;

    ErrorCode(String code, String title, int httpStatus) {
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

    public int getHttpStatus() {
        return httpStatus;
    }
}
