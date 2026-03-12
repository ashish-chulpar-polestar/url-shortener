package com.example.urlshortener.exception;

public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException() {
        super("Failed to generate a unique short code after maximum retry attempts");
    }
}
