package com.example.urlshortener.exception;

public class UrlExpiredException extends RuntimeException {

    public UrlExpiredException(String code) {
        super("Short code has expired: " + code);
    }
}
