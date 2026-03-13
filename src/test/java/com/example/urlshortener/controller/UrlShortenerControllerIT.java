package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ErrorResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UrlShortenerControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void postShorten_shouldReturn201WithCodeAndShortUrl() {
        ShortenRequest req = new ShortenRequest();
        req.setUrl("https://example.com");
        ResponseEntity<ShortenResponse> response = restTemplate.postForEntity("/shorten", req, ShortenResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().getCode().matches("[A-Za-z0-9]{6}"));
        assertTrue(response.getBody().getShortUrl().endsWith(response.getBody().getCode()));
    }

    @Test
    void getRedirect_shouldReturn302WithLocationHeader() {
        ShortenRequest req = new ShortenRequest();
        req.setUrl("https://example.com");
        ResponseEntity<ShortenResponse> shortenResp = restTemplate.postForEntity("/shorten", req, ShortenResponse.class);
        String code = shortenResp.getBody().getCode();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setFollowRedirects(false);
        RestTemplate noRedirectTemplate = new RestTemplate(factory);

        ResponseEntity<Void> resp = noRedirectTemplate.getForEntity("http://localhost:" + port + "/" + code, Void.class);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertEquals("https://example.com", resp.getHeaders().getLocation().toString());
    }

    @Test
    void getRedirect_shouldReturn404ForUnknownCode() {
        ResponseEntity<ErrorResponse> resp = restTemplate.getForEntity("/zzz999", ErrorResponse.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NOT_FOUND", resp.getBody().getError());
    }

    @Test
    void postShorten_shouldReturn400ForInvalidUrl() {
        ShortenRequest req = new ShortenRequest();
        req.setUrl("not-a-url");
        ResponseEntity<ErrorResponse> resp = restTemplate.postForEntity("/shorten", req, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getError());
    }
}
