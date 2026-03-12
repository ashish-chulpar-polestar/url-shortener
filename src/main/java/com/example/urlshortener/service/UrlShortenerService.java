package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 6;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.expiry-days:30}")
    private int expiryDays;

    @Value("${app.max-code-retries:10}")
    private int maxRetries;

    private final ShortUrlRepository repository;
    private final SecureRandom random = new SecureRandom();

    public UrlShortenerService(ShortUrlRepository repository) {
        this.repository = repository;
    }

    public ShortenResponse shorten(String originalUrl) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String code = generateCode();
            if (!repository.existsByCode(code)) {
                Instant now = Instant.now();
                ShortUrl entity = new ShortUrl(code, originalUrl, now, now.plus(expiryDays, ChronoUnit.DAYS));
                repository.save(entity);
                log.info("Shortened {} -> {}", originalUrl, code);
                return new ShortenResponse(code, baseUrl + "/" + code);
            }
            log.debug("Code collision on attempt {}: {}", attempt + 1, code);
        }
        throw new IllegalStateException("Could not generate a unique code after " + maxRetries + " attempts");
    }

    public ShortUrl resolve(String code) {
        ShortUrl shortUrl = repository.findByCode(code)
                .orElseThrow(() -> new CodeNotFoundException(code));
        if (!shortUrl.getExpiresAt().isAfter(Instant.now())) {
            throw new CodeExpiredException(code);
        }
        log.info("Redirect {} -> {}", code, shortUrl.getOriginalUrl());
        return shortUrl;
    }

    String generateCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }
}
