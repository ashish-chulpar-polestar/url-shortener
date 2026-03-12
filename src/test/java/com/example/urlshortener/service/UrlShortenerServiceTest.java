package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exception.ShortCodeExpiredException;
import com.example.urlshortener.exception.ShortCodeNotFoundException;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-03-12T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private CodeGeneratorService codeGenerator;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(repository, codeGenerator, FIXED_CLOCK);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
    }

    @Test
    void shorten_persistsMappingAndReturnsCode() {
        when(codeGenerator.generate()).thenReturn("abc123");
        when(repository.existsByCode("abc123")).thenReturn(false);

        UrlMapping savedMapping = new UrlMapping();
        savedMapping.setCode("abc123");
        when(repository.save(any(UrlMapping.class))).thenReturn(savedMapping);

        ShortenResponse response = service.shorten("https://example.com/long");

        assertThat(response.code()).isEqualTo("abc123");
        assertThat(response.shortUrl()).isEqualTo(BASE_URL + "/abc123");
        verify(repository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    void shorten_retriesOnCollision() {
        when(codeGenerator.generate()).thenReturn("aaa111", "aaa111", "bbb222");
        when(repository.existsByCode("aaa111")).thenReturn(true);
        when(repository.existsByCode("bbb222")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten("https://example.com");

        assertThat(response.code()).isEqualTo("bbb222");
        verify(codeGenerator, times(3)).generate();
        verify(repository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    void shorten_throwsAfterMaxRetries() {
        when(codeGenerator.generate()).thenReturn("xxx999");
        when(repository.existsByCode("xxx999")).thenReturn(true);

        assertThatThrownBy(() -> service.shorten("https://example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shorten_savesCorrectExpiryDate() {
        when(codeGenerator.generate()).thenReturn("abc123");
        when(repository.existsByCode("abc123")).thenReturn(false);

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.shorten("https://example.com");

        UrlMapping saved = captor.getValue();
        assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(30L * 24 * 60 * 60));
    }

    @Test
    void resolve_validCode_returnsOriginalUrl() {
        UrlMapping mapping = new UrlMapping();
        mapping.setCode("abc123");
        mapping.setOriginalUrl("https://example.com/long");
        mapping.setExpiresAt(FIXED_NOW.plusSeconds(86400)); // expires in 1 day

        when(repository.findByCode("abc123")).thenReturn(Optional.of(mapping));

        String result = service.resolve("abc123");

        assertThat(result).isEqualTo("https://example.com/long");
    }

    @Test
    void resolve_expiredCode_throwsShortCodeExpiredException() {
        UrlMapping mapping = new UrlMapping();
        mapping.setCode("abc123");
        mapping.setOriginalUrl("https://example.com/long");
        mapping.setExpiresAt(FIXED_NOW.minusMillis(1)); // expired 1ms ago

        when(repository.findByCode("abc123")).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(ShortCodeExpiredException.class);
    }

    @Test
    void resolve_expiredCode_exactBoundary() {
        UrlMapping mapping = new UrlMapping();
        mapping.setCode("abc123");
        mapping.setOriginalUrl("https://example.com/long");
        mapping.setExpiresAt(FIXED_NOW); // expires exactly now

        when(repository.findByCode("abc123")).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> service.resolve("abc123"))
                .isInstanceOf(ShortCodeExpiredException.class);
    }

    @Test
    void resolve_validCode_oneMillisecondBeforeExpiry() {
        UrlMapping mapping = new UrlMapping();
        mapping.setCode("abc123");
        mapping.setOriginalUrl("https://example.com/long");
        mapping.setExpiresAt(FIXED_NOW.plusMillis(1)); // expires 1ms from now

        when(repository.findByCode("abc123")).thenReturn(Optional.of(mapping));

        String result = service.resolve("abc123");

        assertThat(result).isEqualTo("https://example.com/long");
    }

    @Test
    void resolve_unknownCode_throwsShortCodeNotFoundException() {
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("unknown"))
                .isInstanceOf(ShortCodeNotFoundException.class);
    }
}
