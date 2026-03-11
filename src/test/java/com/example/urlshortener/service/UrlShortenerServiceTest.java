package com.example.urlshortener.service;

import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(urlMappingRepository);
    }

    @Test
    void generateCode_returnsExactlySixAlphanumericCharacters() {
        String code = service.generateCode();

        assertThat(code).hasSize(UrlShortenerService.CODE_LENGTH);
        assertThat(code).matches("[A-Za-z0-9]{6}");
    }

    @Test
    void generateCode_producesVariousCodes() {
        // Generate many codes and verify they vary (not all identical)
        long distinctCount = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> service.generateCode())
                .distinct()
                .count();

        assertThat(distinctCount).isGreaterThan(1);
    }

    @Test
    void shorten_savesAndReturnsMappingWithCode() {
        String url = "https://example.com/some/long/path";
        when(urlMappingRepository.existsByCode(anyString())).thenReturn(false);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.shorten(url);

        assertThat(result.getOriginalUrl()).isEqualTo(url);
        assertThat(result.getCode()).hasSize(6);
        assertThat(result.getCode()).matches("[A-Za-z0-9]{6}");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getExpiresAt()).isNotNull();
        verify(urlMappingRepository).save(any(UrlMapping.class));
    }

    @Test
    void shorten_setsExpiryThirtyDaysFromNow() {
        when(urlMappingRepository.existsByCode(anyString())).thenReturn(false);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.shorten("https://example.com");

        long daysBetween = ChronoUnit.DAYS.between(result.getCreatedAt(), result.getExpiresAt());
        assertThat(daysBetween).isEqualTo(UrlShortenerService.EXPIRY_DAYS);
    }

    @Test
    void shorten_retriesWhenCodeAlreadyExists() {
        when(urlMappingRepository.existsByCode(anyString()))
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);
        when(urlMappingRepository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.shorten("https://example.com");

        assertThat(result.getCode()).isNotNull();
        verify(urlMappingRepository, times(3)).existsByCode(anyString());
    }

    @Test
    void findByCode_returnsEmptyWhenNotFound() {
        when(urlMappingRepository.findByCode("abc123")).thenReturn(Optional.empty());

        Optional<UrlMapping> result = service.findByCode("abc123");

        assertThat(result).isEmpty();
    }

    @Test
    void findByCode_returnsMappingWhenFound() {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com",
                Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        when(urlMappingRepository.findByCode("abc123")).thenReturn(Optional.of(mapping));

        Optional<UrlMapping> result = service.findByCode("abc123");

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo("abc123");
        assertThat(result.get().getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void isExpired_returnsFalseForFutureExpiry() {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com",
                Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(mapping.isExpired()).isFalse();
    }

    @Test
    void isExpired_returnsTrueForPastExpiry() {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com",
                Instant.now().minus(31, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(mapping.isExpired()).isTrue();
    }

    @Test
    void isExpired_returnsTrueWhenExpiryIsNow() {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com",
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.MILLIS));

        assertThat(mapping.isExpired()).isTrue();
    }
}
