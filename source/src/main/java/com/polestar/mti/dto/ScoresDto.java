package com.polestar.mti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ScoresDto {

    @JsonProperty("mti_score")
    private BigDecimal mtiScore;

    @JsonProperty("vessel_score")
    private BigDecimal vesselScore;

    @JsonProperty("reporting_score")
    private BigDecimal reportingScore;

    @JsonProperty("voyages_score")
    private BigDecimal voyagesScore;

    @JsonProperty("emissions_score")
    private BigDecimal emissionsScore;

    @JsonProperty("sanctions_score")
    private BigDecimal sanctionsScore;
}
