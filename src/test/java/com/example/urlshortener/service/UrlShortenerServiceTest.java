package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeGenerationException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlShortenerServiceTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private ShortUrlRepository repository;
    private AppProperties appProperties;
    private Clock fixedClock;
    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShortUrlRepository.class);
        appProperties = mock(AppProperties.class);
        when(appProperties.getBaseUrl()).thenReturn(BASE_URL);
        fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new UrlShortenerService(repository, appProperties, fixedClock);
    }

    @Test
    void generateCode_returnsExactlySixChars() {
        String code = service.generateCode();
        assertThat(code).hasSize(6);
    }

    @Test
    void generateCode_returnsAlphanumericOnly() {
        String code = service.generateCode();
        assertThat(code).matches("[A-Za-z0-9]{6}");
    }

    @Test
    void generateCode_producesDistinctValues() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(service.generateCode());
        }
        assertThat(codes.size()).isGreaterThanOrEqualTo(990);
    }

    @Test
    void shorten_persistsEntityWithCorrectFields() {
        ShortenRequest request = new ShortenRequest();
        request.setUrl("https://example.com/long-path");

        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten(request);

        assertThat(response.getCode()).hasSize(6);
        assertThat(response.getCode()).matches("[A-Za-z0-9]{6}");
    }

    @Test
    void shorten_returnsCorrectShortUrl() {
        ShortenRequest request = new ShortenRequest();
        request.setUrl("https://example.com");

        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten(request);

        assertThat(response.getShortUrl()).startsWith(BASE_URL + "/");
        assertThat(response.getShortUrl()).endsWith(response.getCode());
    }

    @Test
    void shorten_expiresAt30DaysFromCreation() {
        ShortenRequest request = new ShortenRequest();
        request.setUrl("https://example.com");

        OffsetDateTime[] capturedExpiresAt = new OffsetDateTime[1];
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> {
            ShortUrl entity = inv.getArgument(0);
            capturedExpiresAt[0] = entity.getExpiresAt();
            return entity;
        });

        service.shorten(request);

        OffsetDateTime expectedExpiry = OffsetDateTime.now(fixedClock).plusDays(30);
        assertThat(capturedExpiresAt[0]).isEqualTo(expectedExpiry);
    }

    @Test
    void shorten_retriesOnCollision() {
        ShortenRequest request = new ShortenRequest();
        request.setUrl("https://example.com");

        when(repository.save(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten(request);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).hasSize(6);
        verify(repository, times(3)).save(any(ShortUrl.class));
    }

    @Test
    void shorten_throwsAfterFiveCollisions() {
        ShortenRequest request = new ShortenRequest();
        request.setUrl("https://example.com");

        when(repository.save(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.shorten(request))
                .isInstanceOf(CodeGenerationException.class);

        verify(repository, times(5)).save(any(ShortUrl.class));
    }

    @Test
    void resolve_returnsOriginalUrl_whenCodeValidAndNotExpired() {
        OffsetDateTime expiresAt = OffsetDateTime.now(fixedClock).plusDays(1);
        ShortUrl entity = new ShortUrl("abc123", "https://example.com", OffsetDateTime.now(fixedClock), expiresAt);

        when(repository.findByCode("abc123")).thenReturn(Optional.of(entity));

        String result = service.resolve("abc123");

        assertThat(result).isEqualTo("https://example.com");
    }

    @Test
    void resolve_throwsCodeNotFoundException_whenCodeNotInDB() {
        when(repository.findByCode("abc123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(CodeNotFoundException.class)
                .hasMessageContaining("abc123");
    }

    @Test
    void resolve_throwsCodeExpiredException_whenExpired() {
        OffsetDateTime expiresAt = OffsetDateTime.now(fixedClock).minusDays(1);
        ShortUrl entity = new ShortUrl("abc123", "https://example.com", OffsetDateTime.now(fixedClock).minusDays(31), expiresAt);

        when(repository.findByCode("abc123")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(CodeExpiredException.class)
                .hasMessageContaining("abc123");
    }

    @Test
    void resolve_throwsCodeNotFoundException_forInvalidCodeFormat() {
        assertThatThrownBy(() -> service.resolve("!@#$%^"))
                .isInstanceOf(CodeNotFoundException.class);

        verify(repository, never()).findByCode(any());
    }

    @Test
    void resolve_throwsCodeNotFoundException_forShortCode() {
        assertThatThrownBy(() -> service.resolve("abc"))
                .isInstanceOf(CodeNotFoundException.class);

        verify(repository, never()).findByCode(any());
    }

    @Test
    void resolve_notExpired_exactBoundary() {
        OffsetDateTime expiresAt = OffsetDateTime.now(fixedClock);
        ShortUrl entity = new ShortUrl("abc123", "https://example.com", OffsetDateTime.now(fixedClock).minusDays(30), expiresAt);

        when(repository.findByCode("abc123")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(CodeExpiredException.class);
    }
}
