package com.example.urlshortener.exception;

public class CodeExpiredException extends RuntimeException {

    public CodeExpiredException(String code) {
        super("Short code has expired: " + code);
    }
}
