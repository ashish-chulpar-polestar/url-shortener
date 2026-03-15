package com.example.mti.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class MtiScore {

    private final Long id;
    private final String imoNumber;
    private final Integer year;
    private final Integer month;
    private final BigDecimal mtiScore;
    private final BigDecimal vesselScore;
    private final BigDecimal reportingScore;
    private final BigDecimal voyagesScore;
    private final BigDecimal emissionsScore;
    private final BigDecimal sanctionsScore;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public MtiScore(Long id, String imoNumber, Integer year, Integer month,
                    BigDecimal mtiScore, BigDecimal vesselScore, BigDecimal reportingScore,
                    BigDecimal voyagesScore, BigDecimal emissionsScore, BigDecimal sanctionsScore,
                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.imoNumber = imoNumber;
        this.year = year;
        this.month = month;
        this.mtiScore = mtiScore;
        this.vesselScore = vesselScore;
        this.reportingScore = reportingScore;
        this.voyagesScore = voyagesScore;
        this.emissionsScore = emissionsScore;
        this.sanctionsScore = sanctionsScore;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public String getImoNumber() { return imoNumber; }
    public Integer getYear() { return year; }
    public Integer getMonth() { return month; }
    public BigDecimal getMtiScore() { return mtiScore; }
    public BigDecimal getVesselScore() { return vesselScore; }
    public BigDecimal getReportingScore() { return reportingScore; }
    public BigDecimal getVoyagesScore() { return voyagesScore; }
    public BigDecimal getEmissionsScore() { return emissionsScore; }
    public BigDecimal getSanctionsScore() { return sanctionsScore; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
