package com.example.urlshortener.service;

import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.ShortUrlExpiredException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class UrlShortenerService {

    private static final Logger logger = LoggerFactory.getLogger(UrlShortenerService.class);
    private static final int MAX_RETRIES = 5;

    private final ShortUrlRepository repository;
    private final CodeGeneratorService codeGenerator;

    public UrlShortenerService(ShortUrlRepository repository, CodeGeneratorService codeGenerator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
    }

    @Transactional
    public ShortUrl shorten(String url) {
        String code = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String candidate = codeGenerator.generate();
            if (!repository.existsByCode(candidate)) {
                code = candidate;
                break;
            }
            logger.debug("Code collision on attempt {}: {}", attempt + 1, candidate);
        }
        if (code == null) {
            throw new IllegalStateException("Failed to generate unique code after " + MAX_RETRIES + " retries");
        }

        Instant now = Instant.now();
        ShortUrl entity = new ShortUrl(code, url, now, now.plus(30, ChronoUnit.DAYS));
        ShortUrl saved = repository.save(entity);
        logger.info("Shortened URL: code={}, originalUrl={}", saved.getCode(), url);
        return saved;
    }

    @Transactional(readOnly = true)
    public ShortUrl resolve(String code) {
        ShortUrl entity = repository.findByCode(code)
                .orElseThrow(() -> new ShortUrlNotFoundException(code));

        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new ShortUrlExpiredException(code);
        }

        logger.info("Resolved code: code={}, originalUrl={}", code, entity.getOriginalUrl());
        return entity;
    }
}
