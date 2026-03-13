package com.polestar.mti.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MtiScoreControllerIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM mti_scores_history");
        jdbcTemplate.update(
                "INSERT INTO mti_scores_history (imo_number, year, month, mti_score, vessel_score, " +
                "reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                "9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00
        );
        jdbcTemplate.update(
                "INSERT INTO mti_scores_history (imo_number, year, month, mti_score, vessel_score, " +
                "reporting_score, voyages_score, emissions_score, sanctions_score, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                "9123456", 2023, 6, 75.00, 75.00, 75.00, 75.00, 75.00, 75.00
        );
    }

    @Test
    void getLatestScores_returns200() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/imo_number").asText()).isEqualTo("9123456");
        assertThat(body.at("/data/year").asInt()).isEqualTo(2024);
        assertThat(body.at("/data/scores/mti_score").asDouble()).isEqualTo(85.50);
        assertThat(body.at("/meta/request_id").asText()).isNotBlank();

        String requestIdFromBody = body.at("/meta/request_id").asText();
        String requestIdFromHeader = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestIdFromHeader).isEqualTo(requestIdFromBody);
    }

    @Test
    void getScoresByYearAndMonth_returns200() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2023&month=6", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/year").asInt()).isEqualTo(2023);
        assertThat(body.at("/data/month").asInt()).isEqualTo(6);
    }

    @Test
    void unknownImo_returns404() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9999999/mti-scores", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/error_code").asText()).isEqualTo("ERR_101");
    }

    @Test
    void invalidImoFormat_returns400Err103() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/123/mti-scores", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/error_code").asText()).isEqualTo("ERR_103");
    }

    @Test
    void monthWithoutYear_returns400Err102() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?month=6", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/error_code").asText()).isEqualTo("ERR_102");
    }

    @Test
    void invalidMonth_returns400Err104() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/vessels/9123456/mti-scores?year=2023&month=13", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/error_code").asText()).isEqualTo("ERR_104");
    }
}
