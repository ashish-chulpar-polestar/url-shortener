package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UrlShortenerControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ShortUrlRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void postShorten_validUrl_returns201WithCodeAndShortUrl() {
        ShortenRequest request = new ShortenRequest("https://example.com");

        ResponseEntity<ShortenResponse> response = restTemplate.postForEntity(
                "/shorten", request, ShortenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).matches("[A-Za-z0-9]{6}");
        assertThat(response.getBody().shortUrl()).contains(response.getBody().code());
    }

    @Test
    void postShorten_sameUrlTwice_returnsDifferentCodes() {
        ShortenRequest request = new ShortenRequest("https://example.com");

        ResponseEntity<ShortenResponse> first = restTemplate.postForEntity(
                "/shorten", request, ShortenResponse.class);
        ResponseEntity<ShortenResponse> second = restTemplate.postForEntity(
                "/shorten", request, ShortenResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().code()).isNotEqualTo(second.getBody().code());
    }

    @Test
    void postShorten_blankUrl_returns400() {
        ShortenRequest request = new ShortenRequest("");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postShorten_missingUrl_returns400() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/shorten", "{}", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postShorten_ftpUrl_returns400() {
        ShortenRequest request = new ShortenRequest("ftp://bad.com");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postShorten_urlTooLong_returns400() {
        String longUrl = "https://" + "a".repeat(2100);
        ShortenRequest request = new ShortenRequest(longUrl);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getCode_validCode_returns302WithLocation() {
        ShortenRequest request = new ShortenRequest("https://example.com/path");
        ResponseEntity<ShortenResponse> shortenResponse = restTemplate.postForEntity(
                "/shorten", request, ShortenResponse.class);
        String code = shortenResponse.getBody().code();

        ResponseEntity<Void> response = restTemplate.getForEntity("/" + code, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/path");
    }

    @Test
    void getCode_unknownCode_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/zzzzzz", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getCode_expiredCode_returns410() {
        ShortUrl expired = new ShortUrl("exp123", "https://example.com",
                Instant.now().minus(31, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS));
        repository.save(expired);

        ResponseEntity<String> response = restTemplate.getForEntity("/exp123", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
