package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.UrlExpiredException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;
    private static final String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ShortUrlRepository shortUrlRepository;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public UrlShortenerService(ShortUrlRepository shortUrlRepository, AppProperties appProperties) {
        this.shortUrlRepository = shortUrlRepository;
        this.appProperties = appProperties;
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = secureRandom.nextInt(ALPHANUMERIC_CHARS.length());
            sb.append(ALPHANUMERIC_CHARS.charAt(index));
        }
        return sb.toString();
    }

    public ShortUrl shorten(String originalUrl) {
        String candidate = null;
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            candidate = generateCode();
            if (!shortUrlRepository.existsByCode(candidate)) {
                break;
            } else {
                candidate = null;
            }
        }
        if (candidate == null) {
            throw new IllegalStateException("Unable to generate unique short code after " + MAX_CODE_GENERATION_ATTEMPTS + " attempts");
        }

        ShortUrl entity = new ShortUrl();
        entity.setCode(candidate);
        entity.setOriginalUrl(originalUrl);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setExpiresAt(entity.getCreatedAt().plusDays(appProperties.getExpiryDays()));

        ShortUrl saved = shortUrlRepository.save(entity);
        log.info("Shortened url code={} expiresAt={}", saved.getCode(), saved.getExpiresAt());
        return saved;
    }

    public String resolve(String code) {
        Optional<ShortUrl> opt = shortUrlRepository.findByCode(code);
        if (opt.isEmpty()) {
            log.warn("Short code not found code={}", code);
            throw new UrlNotFoundException(code);
        }

        ShortUrl shortUrl = opt.get();
        if (shortUrl.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            log.warn("Short code expired code={} expiresAt={}", code, shortUrl.getExpiresAt());
            throw new UrlExpiredException(code);
        }

        log.info("Resolved code={} to url={}", code, shortUrl.getOriginalUrl());
        return shortUrl.getOriginalUrl();
    }
}
