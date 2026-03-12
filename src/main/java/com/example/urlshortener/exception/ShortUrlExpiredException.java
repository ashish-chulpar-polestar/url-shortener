package com.example.urlshortener.exception;

public class ShortUrlExpiredException extends RuntimeException {

    public ShortUrlExpiredException(String code) {
        super("Short URL has expired: " + code);
    }
}
