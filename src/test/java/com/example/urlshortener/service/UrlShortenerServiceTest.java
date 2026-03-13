package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.UrlExpiredException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;

    @Mock
    private AppProperties appProperties;

    @InjectMocks
    private UrlShortenerService urlShortenerService;

    @BeforeEach
    void setUp() {
        when(appProperties.getExpiryDays()).thenReturn(30);
        when(shortUrlRepository.existsByCode(anyString())).thenReturn(false);
        when(shortUrlRepository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shorten_shouldReturnShortUrlWithSixCharCode() {
        ShortUrl result = urlShortenerService.shorten("https://example.com");
        assertNotNull(result.getCode());
        assertTrue(result.getCode().matches("[A-Za-z0-9]{6}"));
    }

    @Test
    void shorten_shouldSetExpiryToThirtyDaysFromNow() {
        ShortUrl result = urlShortenerService.shorten("https://example.com");
        assertTrue(result.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(29)));
        assertTrue(result.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusDays(31)));
    }

    @Test
    void resolve_shouldThrowUrlNotFoundExceptionWhenCodeMissing() {
        when(shortUrlRepository.findByCode("xxxxxx")).thenReturn(Optional.empty());
        UrlNotFoundException ex = assertThrows(UrlNotFoundException.class, () -> urlShortenerService.resolve("xxxxxx"));
        assertEquals("Short code not found: xxxxxx", ex.getMessage());
    }

    @Test
    void resolve_shouldThrowUrlExpiredExceptionWhenCodeExpired() {
        ShortUrl expired = new ShortUrl();
        expired.setCode("testco");
        expired.setOriginalUrl("https://example.com");
        expired.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(31));
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(shortUrlRepository.findByCode("testco")).thenReturn(Optional.of(expired));
        UrlExpiredException ex = assertThrows(UrlExpiredException.class, () -> urlShortenerService.resolve("testco"));
        assertEquals("Short code has expired: testco", ex.getMessage());
    }

    @Test
    void resolve_shouldReturnOriginalUrlForValidCode() {
        ShortUrl valid = new ShortUrl();
        valid.setCode("abc123");
        valid.setOriginalUrl("https://example.com");
        valid.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        valid.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(10));
        when(shortUrlRepository.findByCode("abc123")).thenReturn(Optional.of(valid));
        String url = urlShortenerService.resolve("abc123");
        assertEquals("https://example.com", url);
    }
}
