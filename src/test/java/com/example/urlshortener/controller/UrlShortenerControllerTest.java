package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.entity.ShortUrl;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.exception.GlobalExceptionHandler;
import com.example.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlShortenerController.class)
@Import(GlobalExceptionHandler.class)
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShortenerService service;

    @Test
    void postShorten_returns200WithCodeAndShortUrl() throws Exception {
        when(service.shorten("https://example.com"))
                .thenReturn(new ShortenResponse("abc123", "http://localhost/abc123"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("abc123"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/abc123"));
    }

    @Test
    void postShorten_returns400WhenUrlMissing() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void postShorten_returns400WhenUrlEmpty() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getCode_returns302WithLocationHeader() throws Exception {
        ShortUrl entity = new ShortUrl(
                "abc123",
                "https://example.com",
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS)
        );
        when(service.resolve("abc123")).thenReturn(entity);

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void getCode_returns404WhenCodeNotFound() throws Exception {
        when(service.resolve("abc123")).thenThrow(new CodeNotFoundException("abc123"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getCode_returns410WhenCodeExpired() throws Exception {
        when(service.resolve("abc123")).thenThrow(new CodeExpiredException("abc123"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").exists());
    }
}
