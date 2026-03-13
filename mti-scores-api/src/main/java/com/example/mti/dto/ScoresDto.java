package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScoresDto(
        @JsonProperty("mti_score") Double mtiScore,
        @JsonProperty("vessel_score") Double vesselScore,
        @JsonProperty("reporting_score") Double reportingScore,
        @JsonProperty("voyages_score") Double voyagesScore,
        @JsonProperty("emissions_score") Double emissionsScore,
        @JsonProperty("sanctions_score") Double sanctionsScore
) {}
