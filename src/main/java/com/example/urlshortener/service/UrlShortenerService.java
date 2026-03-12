package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeGenerationException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_RETRIES = 5;
    private static final String CODE_PATTERN = "[A-Za-z0-9]{6}";

    private final ShortUrlRepository repository;
    private final AppProperties appProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public UrlShortenerService(ShortUrlRepository repository, AppProperties appProperties, Clock clock) {
        this.repository = repository;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public ShortenResponse shorten(ShortenRequest request) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = generateCode();
            OffsetDateTime now = OffsetDateTime.now(clock);
            ShortUrl entity = new ShortUrl(code, request.getUrl(), now, now.plusDays(30));
            try {
                repository.save(entity);
                log.info("Created short code {} for URL {}", code, request.getUrl());
                return new ShortenResponse(code, appProperties.getBaseUrl() + "/" + code);
            } catch (DataIntegrityViolationException e) {
                log.warn("Code collision on attempt {}/{} for code {}", attempt + 1, MAX_RETRIES, code);
            }
        }
        log.error("Exhausted {} retry attempts for code generation", MAX_RETRIES);
        throw new CodeGenerationException();
    }

    public String resolve(String code) {
        if (!code.matches(CODE_PATTERN)) {
            log.debug("Rejecting invalid code format: {}", code);
            throw new CodeNotFoundException(code);
        }
        ShortUrl entity = repository.findByCode(code)
                .orElseThrow(() -> {
                    log.debug("Code not found in DB: {}", code);
                    return new CodeNotFoundException(code);
                });
        if (entity.getExpiresAt().isBefore(OffsetDateTime.now(clock))) {
            log.debug("Code {} has expired at {}", code, entity.getExpiresAt());
            throw new CodeExpiredException(code);
        }
        log.info("Resolving code {} to {}", code, entity.getOriginalUrl());
        return entity.getOriginalUrl();
    }
}
