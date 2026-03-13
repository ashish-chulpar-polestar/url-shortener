package com.example.mti.repository;

import com.example.mti.model.MtiScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class MtiScoresRepository {

    private static final Logger log = LoggerFactory.getLogger(MtiScoresRepository.class);

    private static final RowMapper<MtiScore> ROW_MAPPER = (rs, rowNum) -> new MtiScore(
            rs.getLong("id"),
            rs.getString("imo_number"),
            rs.getInt("year"),
            rs.getInt("month"),
            rs.getBigDecimal("mti_score"),
            rs.getBigDecimal("vessel_score"),
            rs.getBigDecimal("reporting_score"),
            rs.getBigDecimal("voyages_score"),
            rs.getBigDecimal("emissions_score"),
            rs.getBigDecimal("sanctions_score"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbcTemplate;

    public MtiScoresRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MtiScore> findLatest(String imoNumber) {
        log.debug("findLatest imoNumber={}", imoNumber);
        try {
            MtiScore result = jdbcTemplate.queryForObject(
                    "SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1",
                    ROW_MAPPER,
                    imoNumber
            );
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<MtiScore> findLatestByYear(String imoNumber, int year) {
        log.debug("findLatestByYear imoNumber={} year={}", imoNumber, year);
        try {
            MtiScore result = jdbcTemplate.queryForObject(
                    "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1",
                    ROW_MAPPER,
                    imoNumber, year
            );
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<MtiScore> findByYearAndMonth(String imoNumber, int year, int month) {
        log.debug("findByYearAndMonth imoNumber={} year={} month={}", imoNumber, year, month);
        try {
            MtiScore result = jdbcTemplate.queryForObject(
                    "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1",
                    ROW_MAPPER,
                    imoNumber, year, month
            );
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
