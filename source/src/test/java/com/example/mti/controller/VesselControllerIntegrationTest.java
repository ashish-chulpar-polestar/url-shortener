package com.example.mti.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Sql(scripts = "/db/test-data.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql", executionPhase = AFTER_TEST_METHOD)
class VesselControllerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void getLatestScores_returns200() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores"), Map.class);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        Map<String, Object> meta = (Map<String, Object>) body.get("meta");
        assertNotNull(meta.get("request_id"));
        assertFalse(((String) meta.get("request_id")).isEmpty());

        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals("9123456", data.get("imo_number"));
        assertEquals(2024, data.get("year"));
        assertEquals(1, data.get("month"));

        Map<String, Object> scores = (Map<String, Object>) data.get("scores");
        assertEquals(85.5, ((Number) scores.get("mti_score")).doubleValue());
    }

    @Test
    void getScoresByYear_returnsLatestMonth() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023"), Map.class);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(2023, data.get("year"));
        assertEquals(12, data.get("month"));
    }

    @Test
    void getScoresByYearAndMonth_returnsExactMonth() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023&month=6"), Map.class);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(6, data.get("month"));

        Map<String, Object> scores = (Map<String, Object>) data.get("scores");
        assertEquals(75.0, ((Number) scores.get("mti_score")).doubleValue());
    }

    @Test
    void getScores_imoNotFound_returns404ERR101() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9999999/mti-scores"), Map.class);

        assertEquals(404, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("ERR_101", data.get("error_code"));
        assertEquals("Resource Not Found", data.get("title"));
    }

    @Test
    void getScores_invalidImo_returns400ERR103() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/123/mti-scores"), Map.class);

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("ERR_103", data.get("error_code"));
    }

    @Test
    void getScores_monthWithoutYear_returns400ERR102() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?month=6"), Map.class);

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("ERR_102", data.get("error_code"));
        assertEquals("Month parameter requires year parameter to be specified", data.get("message"));
    }

    @Test
    void getScores_invalidMonth_returns400ERR104() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9123456/mti-scores?year=2023&month=13"), Map.class);

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("ERR_104", data.get("error_code"));
    }

    @Test
    void getScores_partialNullScores_returnsNullFields() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/v1/vessels/9999998/mti-scores?year=2024&month=3"), Map.class);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Map<String, Object> scores = (Map<String, Object>) data.get("scores");

        assertNull(scores.get("mti_score"));
        assertEquals(88.0, ((Number) scores.get("vessel_score")).doubleValue());
        assertNull(scores.get("reporting_score"));
    }
}
