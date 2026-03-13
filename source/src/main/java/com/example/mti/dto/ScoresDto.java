package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class ScoresDto {

    @JsonProperty("mti_score")
    private final BigDecimal mtiScore;

    @JsonProperty("vessel_score")
    private final BigDecimal vesselScore;

    @JsonProperty("reporting_score")
    private final BigDecimal reportingScore;

    @JsonProperty("voyages_score")
    private final BigDecimal voyagesScore;

    @JsonProperty("emissions_score")
    private final BigDecimal emissionsScore;

    @JsonProperty("sanctions_score")
    private final BigDecimal sanctionsScore;

    public ScoresDto(BigDecimal mtiScore, BigDecimal vesselScore, BigDecimal reportingScore,
                     BigDecimal voyagesScore, BigDecimal emissionsScore, BigDecimal sanctionsScore) {
        this.mtiScore = mtiScore;
        this.vesselScore = vesselScore;
        this.reportingScore = reportingScore;
        this.voyagesScore = voyagesScore;
        this.emissionsScore = emissionsScore;
        this.sanctionsScore = sanctionsScore;
    }

    public BigDecimal getMtiScore() { return mtiScore; }
    public BigDecimal getVesselScore() { return vesselScore; }
    public BigDecimal getReportingScore() { return reportingScore; }
    public BigDecimal getVoyagesScore() { return voyagesScore; }
    public BigDecimal getEmissionsScore() { return emissionsScore; }
    public BigDecimal getSanctionsScore() { return sanctionsScore; }
}
