package com.example.urlshortener.service;

import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.ShortUrlExpiredException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository repository;

    @Mock
    private CodeGeneratorService codeGenerator;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(repository, codeGenerator);
    }

    @Test
    void shorten_savesEntityWithExpiryIn30Days() {
        when(codeGenerator.generate()).thenReturn("abc123");
        when(repository.existsByCode("abc123")).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortUrl result = service.shorten("https://example.com");

        assertThat(result.getExpiresAt())
                .isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void shorten_returnsEntityWithGeneratedCode() {
        when(codeGenerator.generate()).thenReturn("abc123");
        when(repository.existsByCode("abc123")).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortUrl result = service.shorten("https://example.com");

        assertThat(result.getCode()).isEqualTo("abc123");
    }

    @Test
    void shorten_retriesOnCollisionAndSucceeds() {
        when(codeGenerator.generate()).thenReturn("aaa111", "bbb222", "ccc333");
        when(repository.existsByCode("aaa111")).thenReturn(true);
        when(repository.existsByCode("bbb222")).thenReturn(true);
        when(repository.existsByCode("ccc333")).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortUrl result = service.shorten("https://example.com");

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("ccc333");
        assertThat(result.getCode()).isEqualTo("ccc333");
    }

    @Test
    void shorten_throwsAfterMaxRetries() {
        when(codeGenerator.generate()).thenReturn("aaa111");
        when(repository.existsByCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.shorten("https://example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate unique code");
    }

    @Test
    void resolve_throwsNotFoundException_whenCodeMissing() {
        when(repository.findByCode("zzzzzz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("zzzzzz"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void resolve_throwsExpiredException_whenExpired() {
        ShortUrl expired = new ShortUrl("abc123", "https://example.com",
                Instant.now().minus(31, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS));
        when(repository.findByCode("abc123")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(ShortUrlExpiredException.class);
    }

    @Test
    void resolve_returnsEntity_whenValid() {
        ShortUrl valid = new ShortUrl("abc123", "https://example.com",
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(29, ChronoUnit.DAYS));
        when(repository.findByCode("abc123")).thenReturn(Optional.of(valid));

        ShortUrl result = service.resolve("abc123");

        assertThat(result.getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void resolve_atExpiryBoundary_isExpired() {
        ShortUrl atBoundary = new ShortUrl("abc123", "https://example.com",
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.MILLIS));
        when(repository.findByCode("abc123")).thenReturn(Optional.of(atBoundary));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(ShortUrlExpiredException.class);
    }
}
