package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

public class ShortenRequest {

    @NotBlank(message = "url must not be blank")
    @URL(message = "url must be a valid URL")
    private String url;

    public ShortenRequest() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
