package com.example.mti.integration;

import com.example.mti.dto.ApiResponse;
import com.example.mti.dto.MtiScoreDataDto;
import com.example.mti.dto.ErrorDataDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class MtiScoresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ac1_getLatestScores() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isNotBlank();

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("imo_number")).isEqualTo("9123456");
        assertThat(data.get("year")).isEqualTo(2024);
        assertThat(data.get("month")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> scores = (java.util.LinkedHashMap<String, Object>) data.get("scores");
        assertThat(new BigDecimal(scores.get("mti_score").toString())).isEqualByComparingTo(new BigDecimal("85.50"));
    }

    @Test
    void ac2_getByYear() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("year")).isEqualTo(2023);
        assertThat(data.get("month")).isEqualTo(12);
    }

    @Test
    void ac3_getByYearAndMonth() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023&month=6"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("month")).isEqualTo(6);
    }

    @Test
    void ac4_imoNotFound() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9999999/mti-scores"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("error_code")).isEqualTo("ERR_101");
    }

    @Test
    void ac5_invalidImoFormat() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/123/mti-scores"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("error_code")).isEqualTo("ERR_103");
    }

    @Test
    void ac6_monthWithoutYear() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?month=6"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("error_code")).isEqualTo("ERR_102");
    }

    @Test
    void ac7_invalidMonthValue() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023&month=13"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        assertThat(data.get("error_code")).isEqualTo("ERR_104");
    }

    @Test
    void ac8_partialNullScores() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123457/mti-scores"), ApiResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> data = (java.util.LinkedHashMap<String, Object>) response.getBody().getData();
        @SuppressWarnings("unchecked")
        java.util.LinkedHashMap<String, Object> scores = (java.util.LinkedHashMap<String, Object>) data.get("scores");
        assertThat(scores.get("mti_score")).isNull();
        assertThat(new BigDecimal(scores.get("vessel_score").toString())).isEqualByComparingTo(new BigDecimal("88.00"));
    }
}
