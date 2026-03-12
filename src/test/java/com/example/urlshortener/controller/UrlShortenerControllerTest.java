package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exception.CodeExpiredException;
import com.example.urlshortener.exception.CodeNotFoundException;
import com.example.urlshortener.exception.GlobalExceptionHandler;
import com.example.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
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
    void postShorten_returns200_withValidUrl() throws Exception {
        when(service.shorten(any())).thenReturn(new ShortenResponse("abc123", "http://localhost:8080/abc123"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/long-path\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("abc123"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abc123"));
    }

    @Test
    void postShorten_returns400_whenUrlMissing() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void postShorten_returns400_whenUrlBlank() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void postShorten_returns400_whenUrlNotHttpScheme() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void postShorten_returns400_whenUrlIsJavascriptScheme() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getCode_returns302_withLocationHeader() throws Exception {
        when(service.resolve("abc123")).thenReturn("https://example.com/long-path");

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/long-path"));
    }

    @Test
    void getCode_returns404_whenNotFound() throws Exception {
        when(service.resolve("abc123")).thenThrow(new CodeNotFoundException("abc123"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Short code not found: abc123"));
    }

    @Test
    void getCode_returns410_whenExpired() throws Exception {
        when(service.resolve("abc123")).thenThrow(new CodeExpiredException("abc123"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("Short code has expired: abc123"));
    }

    @Test
    void getCode_returns503_whenDbUnavailable() throws Exception {
        when(service.resolve("abc123")).thenThrow(new DataAccessResourceFailureException("DB down"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Service temporarily unavailable"));
    }
}
