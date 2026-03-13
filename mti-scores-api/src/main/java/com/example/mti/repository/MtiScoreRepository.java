package com.example.mti.repository;

import com.example.mti.model.MtiScoreRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MtiScoreRepository {

    private static final Logger log = LoggerFactory.getLogger(MtiScoreRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<MtiScoreRecord> ROW_MAPPER = (rs, rowNum) -> new MtiScoreRecord(
            rs.getLong("id"),
            rs.getString("imo_number"),
            rs.getInt("year"),
            rs.getInt("month"),
            rs.getObject("mti_score", Double.class),
            rs.getObject("vessel_score", Double.class),
            rs.getObject("reporting_score", Double.class),
            rs.getObject("voyages_score", Double.class),
            rs.getObject("emissions_score", Double.class),
            rs.getObject("sanctions_score", Double.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    public MtiScoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MtiScoreRecord> findLatest(String imoNumber) {
        log.debug("findLatest imo={}", imoNumber);
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1";
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, ROW_MAPPER, imoNumber);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    public Optional<MtiScoreRecord> findLatestByYear(String imoNumber, int year) {
        log.debug("findLatestByYear imo={} year={}", imoNumber, year);
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1";
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, ROW_MAPPER, imoNumber, year);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    public Optional<MtiScoreRecord> findByYearAndMonth(String imoNumber, int year, int month) {
        log.debug("findByYearAndMonth imo={} year={} month={}", imoNumber, year, month);
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1";
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, ROW_MAPPER, imoNumber, year, month);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }
}
