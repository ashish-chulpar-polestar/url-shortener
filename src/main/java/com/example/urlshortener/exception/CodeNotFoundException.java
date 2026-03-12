package com.example.urlshortener.exception;

public class CodeNotFoundException extends RuntimeException {

    public CodeNotFoundException(String code) {
        super("Short code not found: " + code);
    }
}
