package com.example.mti.repository;

import com.example.mti.model.MtiScoreRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

@Component
public class MtiScoreRowMapper implements RowMapper<MtiScoreRecord> {

    @Override
    public MtiScoreRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String imoNumber = rs.getString("imo_number");
        int year = rs.getInt("year");
        int month = rs.getInt("month");
        Double mtiScore = rs.getObject("mti_score", Double.class);
        Double vesselScore = rs.getObject("vessel_score", Double.class);
        Double reportingScore = rs.getObject("reporting_score", Double.class);
        Double voyagesScore = rs.getObject("voyages_score", Double.class);
        Double emissionsScore = rs.getObject("emissions_score", Double.class);
        Double sanctionsScore = rs.getObject("sanctions_score", Double.class);

        OffsetDateTime createdAtOdt = rs.getObject("created_at", OffsetDateTime.class);
        Instant createdAt = createdAtOdt != null ? createdAtOdt.toInstant() : null;

        OffsetDateTime updatedAtOdt = rs.getObject("updated_at", OffsetDateTime.class);
        Instant updatedAt = updatedAtOdt != null ? updatedAtOdt.toInstant() : null;

        return new MtiScoreRecord(imoNumber, year, month, mtiScore, vesselScore,
                reportingScore, voyagesScore, emissionsScore, sanctionsScore,
                createdAt, updatedAt);
    }
}
