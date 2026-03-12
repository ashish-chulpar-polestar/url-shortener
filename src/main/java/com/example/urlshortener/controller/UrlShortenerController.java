package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.service.UrlShortenerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class UrlShortenerController {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerController.class);

    private final UrlShortenerService service;

    public UrlShortenerController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@RequestBody @Valid ShortenRequest request) {
        ShortenResponse response = service.shorten(request.url());
        log.info("Shortened {} -> {}", request.url(), response.code());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortUrl shortUrl = service.resolve(code);
        log.info("Redirect {} -> {}", code, shortUrl.getOriginalUrl());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shortUrl.getOriginalUrl()))
                .build();
    }
}
