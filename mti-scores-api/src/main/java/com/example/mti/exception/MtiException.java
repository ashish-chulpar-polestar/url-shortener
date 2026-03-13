package com.example.mti.exception;

import com.example.mti.constant.ErrorCode;

public abstract class MtiException extends RuntimeException {

    private final ErrorCode errorCode;

    protected MtiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected MtiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
