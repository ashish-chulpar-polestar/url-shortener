package com.example.mti.repository;

import com.example.mti.model.MtiScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class MtiScoresRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MtiScoresRepository repository;

    @Test
    void findLatest_returnsHighestYearMonth() {
        Optional<MtiScore> result = repository.findLatest("9123456");

        assertThat(result).isPresent();
        assertThat(result.get().getYear()).isEqualTo(2024);
        assertThat(result.get().getMonth()).isEqualTo(1);
        assertThat(result.get().getMtiScore()).isEqualByComparingTo(new BigDecimal("85.50"));
        assertThat(result.get().getImoNumber()).isEqualTo("9123456");
    }

    @Test
    void findLatestByYear_year2023_returnsDecemberRow() {
        Optional<MtiScore> result = repository.findLatestByYear("9123456", 2023);

        assertThat(result).isPresent();
        assertThat(result.get().getYear()).isEqualTo(2023);
        assertThat(result.get().getMonth()).isEqualTo(12);
        assertThat(result.get().getMtiScore()).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    void findByYearAndMonth_2023_6_returnsJuneRow() {
        Optional<MtiScore> result = repository.findByYearAndMonth("9123456", 2023, 6);

        assertThat(result).isPresent();
        assertThat(result.get().getMonth()).isEqualTo(6);
        assertThat(result.get().getMtiScore()).isEqualByComparingTo(new BigDecimal("75.25"));
    }

    @Test
    void findLatest_unknownImo_returnsEmpty() {
        Optional<MtiScore> result = repository.findLatest("0000000");

        assertThat(result).isEmpty();
    }
}
