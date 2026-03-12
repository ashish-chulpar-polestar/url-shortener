package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exception.ShortCodeExpiredException;
import com.example.urlshortener.exception.ShortCodeNotFoundException;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class UrlShortenerService {

    private static final Logger logger = LoggerFactory.getLogger(UrlShortenerService.class);
    private static final int MAX_RETRIES = 5;
    private static final int EXPIRY_DAYS = 30;

    private final UrlMappingRepository repository;
    private final CodeGeneratorService codeGenerator;
    private final Clock clock;

    @Value("${app.base-url}")
    private String baseUrl;

    public UrlShortenerService(UrlMappingRepository repository, CodeGeneratorService codeGenerator, Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    public ShortenResponse shorten(String url) {
        String code = generateUniqueCode();
        Instant now = Instant.now(clock);
        UrlMapping mapping = new UrlMapping();
        mapping.setCode(code);
        mapping.setOriginalUrl(url);
        mapping.setCreatedAt(now);
        mapping.setExpiresAt(now.plus(EXPIRY_DAYS, ChronoUnit.DAYS));
        repository.save(mapping);
        logger.info("Shortened URL to code={}", code);
        return new ShortenResponse(code, baseUrl + "/" + code);
    }

    public String resolve(String code) {
        UrlMapping mapping = repository.findByCode(code)
                .orElseThrow(ShortCodeNotFoundException::new);
        if (!Instant.now(clock).isBefore(mapping.getExpiresAt())) {
            logger.debug("Short code expired: code={}", code);
            throw new ShortCodeExpiredException();
        }
        logger.debug("Resolved code={} to url={}", code, mapping.getOriginalUrl());
        return mapping.getOriginalUrl();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = codeGenerator.generate();
            if (!repository.existsByCode(code)) {
                return code;
            }
            logger.debug("Code collision on attempt {}, retrying", attempt + 1);
        }
        logger.error("Unable to generate unique code after {} attempts", MAX_RETRIES);
        throw new IllegalStateException("Unable to generate unique code after " + MAX_RETRIES + " attempts");
    }
}
