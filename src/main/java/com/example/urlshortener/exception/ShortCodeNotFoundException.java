package com.example.urlshortener.exception;

public class ShortCodeNotFoundException extends RuntimeException {

    public ShortCodeNotFoundException() {
        super("Short code not found");
    }
}
