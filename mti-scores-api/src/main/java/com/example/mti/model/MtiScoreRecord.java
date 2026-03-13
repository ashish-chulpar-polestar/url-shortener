package com.example.mti.model;

import java.time.OffsetDateTime;

public record MtiScoreRecord(
        Long id,
        String imoNumber,
        Integer year,
        Integer month,
        Double mtiScore,
        Double vesselScore,
        Double reportingScore,
        Double voyagesScore,
        Double emissionsScore,
        Double sanctionsScore,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
