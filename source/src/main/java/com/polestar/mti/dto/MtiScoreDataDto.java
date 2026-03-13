package com.polestar.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MtiScoreDataDto {

    @JsonProperty("imo_number")
    private String imoNumber;

    private Integer year;

    private Integer month;

    private ScoresDto scores;

    private MetadataDto metadata;
}
