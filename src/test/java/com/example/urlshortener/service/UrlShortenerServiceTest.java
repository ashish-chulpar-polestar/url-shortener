package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository repository;

    @InjectMocks
    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "expiryDays", 30);
        ReflectionTestUtils.setField(service, "maxRetries", 10);
    }

    @Test
    void generateCode_matchesAlphanumericPattern() {
        for (int i = 0; i < 1000; i++) {
            String code = service.generateCode();
            assertThat(code).matches("[A-Za-z0-9]{6}");
        }
    }

    @Test
    void generateCode_producesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(service.generateCode());
        }
        assertThat(codes.size()).isGreaterThan(1);
    }

    @Test
    void shorten_savesEntityAndReturnsResponse() {
        when(repository.existsByCode(anyString())).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten("https://example.com");

        verify(repository, times(1)).save(any(ShortUrl.class));
        assertThat(response.code()).matches("[A-Za-z0-9]{6}");
        assertThat(response.shortUrl()).startsWith("http://localhost:8080/");
    }

    @Test
    void shorten_retriesOnCollisionAndSucceeds() {
        when(repository.existsByCode(anyString()))
                .thenReturn(true)
                .thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten("https://example.com");

        verify(repository, times(2)).existsByCode(anyString());
        verify(repository, times(1)).save(any(ShortUrl.class));
        assertThat(response.code()).isNotNull();
    }

    @Test
    void shorten_throwsIllegalStateExceptionWhenRetriesExhausted() {
        when(repository.existsByCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.shorten("https://example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not generate a unique code");
    }

    @Test
    void shorten_duplicateLongUrlsProduceDistinctCodes() {
        when(repository.existsByCode(anyString())).thenReturn(false);
        when(repository.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse first = service.shorten("https://example.com");
        ShortenResponse second = service.shorten("https://example.com");

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        verify(repository, times(2)).save(any(ShortUrl.class));
    }

    @Test
    void resolve_returnsShortUrlWhenValidAndNotExpired() {
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        ShortUrl entity = new ShortUrl("abc123", "https://example.com", Instant.now(), future);
        when(repository.findByCode("abc123")).thenReturn(Optional.of(entity));

        ShortUrl result = service.resolve("abc123");

        assertThat(result).isEqualTo(entity);
    }

    @Test
    void resolve_throwsCodeExpiredExceptionWhenExpired() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        ShortUrl entity = new ShortUrl("abc123", "https://example.com", past.minus(30, ChronoUnit.DAYS), past);
        when(repository.findByCode("abc123")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(CodeExpiredException.class)
                .hasMessageContaining("abc123");
    }

    @Test
    void resolve_throwsCodeNotFoundExceptionWhenAbsent() {
        when(repository.findByCode("xyz999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("xyz999"))
                .isInstanceOf(CodeNotFoundException.class)
                .hasMessageContaining("xyz999");
    }
}
