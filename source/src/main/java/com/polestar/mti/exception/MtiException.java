package com.polestar.mti.exception;

import com.polestar.mti.constant.ErrorCode;

public class MtiException extends RuntimeException {

    private final ErrorCode errorCode;

    public MtiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MtiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getTitle());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
