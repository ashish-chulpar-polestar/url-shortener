package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MtiScoreDataDto {

    @JsonProperty("imo_number")
    private final String imoNumber;

    private final Integer year;
    private final Integer month;
    private final ScoresDto scores;
    private final MetadataDto metadata;

    public MtiScoreDataDto(String imoNumber, Integer year, Integer month,
                           ScoresDto scores, MetadataDto metadata) {
        this.imoNumber = imoNumber;
        this.year = year;
        this.month = month;
        this.scores = scores;
        this.metadata = metadata;
    }

    public String getImoNumber() { return imoNumber; }
    public Integer getYear() { return year; }
    public Integer getMonth() { return month; }
    public ScoresDto getScores() { return scores; }
    public MetadataDto getMetadata() { return metadata; }
}
