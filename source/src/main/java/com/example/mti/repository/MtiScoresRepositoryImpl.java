package com.example.mti.repository;

import com.example.mti.model.MtiScoreRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MtiScoresRepositoryImpl implements MtiScoresRepository {

    private static final Logger log = LoggerFactory.getLogger(MtiScoresRepositoryImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final MtiScoreRowMapper rowMapper;

    public MtiScoresRepositoryImpl(JdbcTemplate jdbcTemplate, MtiScoreRowMapper rowMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = rowMapper;
    }

    @Override
    public Optional<MtiScoreRecord> findLatest(String imoNumber) {
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? ORDER BY year DESC, month DESC LIMIT 1";
        log.debug("findLatest imo={}", imoNumber);
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, rowMapper, imoNumber);
        return Optional.ofNullable(results.isEmpty() ? null : results.get(0));
    }

    @Override
    public Optional<MtiScoreRecord> findLatestByYear(String imoNumber, int year) {
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? ORDER BY month DESC LIMIT 1";
        log.debug("findLatestByYear imo={} year={}", imoNumber, year);
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, rowMapper, imoNumber, year);
        return Optional.ofNullable(results.isEmpty() ? null : results.get(0));
    }

    @Override
    public Optional<MtiScoreRecord> findByYearAndMonth(String imoNumber, int year, int month) {
        String sql = "SELECT * FROM mti_scores_history WHERE imo_number = ? AND year = ? AND month = ? LIMIT 1";
        log.debug("findByYearAndMonth imo={} year={} month={}", imoNumber, year, month);
        List<MtiScoreRecord> results = jdbcTemplate.query(sql, rowMapper, imoNumber, year, month);
        return Optional.ofNullable(results.isEmpty() ? null : results.get(0));
    }
}
