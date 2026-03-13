package com.example.mti.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MtiScoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void ac1_latestScores_returns200WithImo9123456() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("9123456", body.path("data").path("imo_number").asText());
        assertEquals(2024, body.path("data").path("year").asInt());
        assertEquals(1, body.path("data").path("month").asInt());
    }

    @Test
    void ac2_specificYear_returnsLatestMonthFor2023() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2023", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(2023, body.path("data").path("year").asInt());
        assertEquals(12, body.path("data").path("month").asInt());
    }

    @Test
    void ac3_specificYearAndMonth_returnsJune2023() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2023&month=6", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(2023, body.path("data").path("year").asInt());
        assertEquals(6, body.path("data").path("month").asInt());
        assertEquals(80.0, body.path("data").path("scores").path("mti_score").asDouble(), 0.001);
    }

    @Test
    void ac4_imoNotFound_returns404WithErrCode101() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9999999/mti-scores", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ERR_101", body.path("data").path("error_code").asText());
        assertTrue(body.path("data").path("message").asText().contains("9999999"));
    }

    @Test
    void ac5_invalidImoFormat_returns400WithErrCode103() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/123/mti-scores", String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ERR_103", body.path("data").path("error_code").asText());
    }

    @Test
    void ac6_monthWithoutYear_returns400WithErrCode102() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?month=6", String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ERR_102", body.path("data").path("error_code").asText());
    }

    @Test
    void ac7_invalidMonthValue13_returns400WithErrCode104() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2023&month=13", String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ERR_104", body.path("data").path("error_code").asText());
    }

    @Test
    void ac8_partialNullScores_returnsNullFieldsInJson() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2022&month=3", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertTrue(body.path("data").path("scores").path("vessel_score").isNull());
        assertTrue(body.path("data").path("scores").path("voyages_score").isNull());
        assertEquals(75.0, body.path("data").path("scores").path("mti_score").asDouble(), 0.001);
    }
}
