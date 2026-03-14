package com.example.mti.model;

import java.time.Instant;

public record MtiScoreRecord(
        String imoNumber,
        int year,
        int month,
        Double mtiScore,
        Double vesselScore,
        Double reportingScore,
        Double voyagesScore,
        Double emissionsScore,
        Double sanctionsScore,
        Instant createdAt,
        Instant updatedAt
) {
}
