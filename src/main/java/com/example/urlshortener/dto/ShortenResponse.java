package com.example.urlshortener.dto;

public class ShortenResponse {

    private final String code;
    private final String shortUrl;

    public ShortenResponse(String code, String shortUrl) {
        this.code = code;
        this.shortUrl = shortUrl;
    }

    public String getCode() {
        return code;
    }

    public String getShortUrl() {
        return shortUrl;
    }
}
