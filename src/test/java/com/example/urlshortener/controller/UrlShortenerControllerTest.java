package com.example.urlshortener.controller;

import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.service.UrlShortenerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlShortenerController.class)
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UrlShortenerService urlShortenerService;

    @Test
    void postShorten_returnsCreatedWithCodeAndShortUrl() throws Exception {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com/long",
                Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        when(urlShortenerService.shorten("https://example.com/long")).thenReturn(mapping);

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", "https://example.com/long"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("abc123"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/abc123"));
    }

    @Test
    void postShorten_returnsBadRequestForBlankUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postShorten_returnsBadRequestForInvalidUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", "not-a-url"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postShorten_returnsBadRequestForMissingBody() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCode_redirectsToOriginalUrl() throws Exception {
        UrlMapping mapping = new UrlMapping(
                "abc123", "https://example.com/destination",
                Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        when(urlShortenerService.findByCode("abc123")).thenReturn(Optional.of(mapping));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"));
    }

    @Test
    void getCode_returns404ForUnknownCode() throws Exception {
        when(urlShortenerService.findByCode(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCode_returns410ForExpiredCode() throws Exception {
        UrlMapping expiredMapping = new UrlMapping(
                "exp123", "https://example.com/old",
                Instant.now().minus(31, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS));
        when(urlShortenerService.findByCode("exp123")).thenReturn(Optional.of(expiredMapping));

        mockMvc.perform(get("/exp123"))
                .andExpect(status().isGone());
    }
}
