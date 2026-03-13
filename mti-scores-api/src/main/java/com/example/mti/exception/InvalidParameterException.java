package com.example.mti.exception;

import com.example.mti.constant.ErrorCode;

public class InvalidParameterException extends MtiException {

    public InvalidParameterException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static InvalidParameterException monthWithoutYear() {
        return new InvalidParameterException(ErrorCode.ERR_102,
                "Month parameter requires year parameter to be specified");
    }

    public static InvalidParameterException invalidImoFormat(String imo) {
        return new InvalidParameterException(ErrorCode.ERR_103,
                "IMO number must be exactly 7 digits, received: " + imo);
    }

    public static InvalidParameterException invalidDateRange(String detail) {
        return new InvalidParameterException(ErrorCode.ERR_104,
                "Invalid year or month value: " + detail);
    }
}
