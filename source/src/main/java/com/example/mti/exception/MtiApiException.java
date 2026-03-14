package com.example.mti.exception;

public class MtiApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detailMessage;

    public MtiApiException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.detailMessage = detailMessage;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetailMessage() {
        return detailMessage;
    }
}
