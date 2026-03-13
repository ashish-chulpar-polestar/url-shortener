package com.example.mti.exception;

import com.example.mti.constant.ErrorCode;

public class ResourceNotFoundException extends MtiException {

    public ResourceNotFoundException(String imoNumber) {
        super(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber);
    }

    public ResourceNotFoundException() {
        super(ErrorCode.ERR_101);
    }
}
