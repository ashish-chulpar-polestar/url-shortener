package com.example.urlshortener.exception;

public class ShortCodeExpiredException extends RuntimeException {

    public ShortCodeExpiredException() {
        super("Short URL has expired");
    }
}
