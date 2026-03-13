package com.example.mti.constant;

public enum ErrorCode {

    ERR_101(404, "Resource Not Found", "No MTI scores found for the given IMO number"),
    ERR_102(400, "Invalid Parameters", "Month parameter requires year parameter to be specified"),
    ERR_103(400, "Invalid IMO Format", "IMO number must be exactly 7 digits"),
    ERR_104(400, "Invalid Date Range", "Invalid year or month value"),
    ERR_105(500, "Internal Server Error", "An internal error occurred");

    private final int httpStatus;
    private final String title;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String title, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.title = title;
        this.defaultMessage = defaultMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getTitle() {
        return title;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public String getCode() {
        return this.name();
    }
}
