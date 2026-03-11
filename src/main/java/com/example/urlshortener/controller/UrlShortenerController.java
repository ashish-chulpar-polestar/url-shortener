package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
public class UrlShortenerController {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerController.class);

    private final UrlShortenerService urlShortenerService;

    public UrlShortenerController(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(
            @Valid @RequestBody ShortenRequest request,
            HttpServletRequest httpRequest) {
        log.info("POST /shorten for URL: {}", request.getUrl());
        UrlMapping mapping = urlShortenerService.shorten(request.getUrl());
        String baseUrl = getBaseUrl(httpRequest);
        String shortUrl = baseUrl + "/" + mapping.getCode();
        ShortenResponse response = new ShortenResponse(mapping.getCode(), shortUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        log.info("GET /{} - resolving short code", code);
        Optional<UrlMapping> mappingOpt = urlShortenerService.findByCode(code);

        if (mappingOpt.isEmpty()) {
            log.debug("Code {} not found", code);
            return ResponseEntity.notFound().build();
        }

        UrlMapping mapping = mappingOpt.get();
        if (mapping.isExpired()) {
            log.info("Code {} has expired", code);
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        log.info("Redirecting code {} to {}", code, mapping.getOriginalUrl());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, mapping.getOriginalUrl())
                .build();
    }

    private String getBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}
