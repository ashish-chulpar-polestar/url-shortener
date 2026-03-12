package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void health_returns200() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void health_returnsStatusOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void health_returnsTimestampAsIso8601String() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void health_responseHasExactlyTwoFields() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
