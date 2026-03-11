package com.example.urlshortener.service;

import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    static final int CODE_LENGTH = 6;
    static final int EXPIRY_DAYS = 30;
    static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final UrlMappingRepository urlMappingRepository;
    private final SecureRandom secureRandom;

    public UrlShortenerService(UrlMappingRepository urlMappingRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.secureRandom = new SecureRandom();
    }

    @Transactional
    public UrlMapping shorten(String originalUrl) {
        log.info("Shortening URL: {}", originalUrl);
        String code = generateUniqueCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(EXPIRY_DAYS, ChronoUnit.DAYS);
        UrlMapping mapping = new UrlMapping(code, originalUrl, now, expiresAt);
        UrlMapping saved = urlMappingRepository.save(mapping);
        log.info("Created short code {} for URL {}", code, originalUrl);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<UrlMapping> findByCode(String code) {
        log.debug("Looking up code: {}", code);
        return urlMappingRepository.findByCode(code);
    }

    String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(secureRandom.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            code = generateCode();
            attempts++;
            log.debug("Generated candidate code {} (attempt {})", code, attempts);
        } while (urlMappingRepository.existsByCode(code));
        return code;
    }
}
