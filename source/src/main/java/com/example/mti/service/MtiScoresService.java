package com.example.mti.service;

import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.MetadataDto;
import com.example.mti.dto.MtiScoreDataDto;
import com.example.mti.dto.ScoresDto;
import com.example.mti.exception.MtiException;
import com.example.mti.model.MtiScore;
import com.example.mti.repository.MtiScoresRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class MtiScoresService {

    private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class);

    private final MtiScoresRepository repository;

    public MtiScoresService(MtiScoresRepository repository) {
        this.repository = repository;
    }

    public MtiScoreDataDto getScores(String imoNumber, Integer year, Integer month) {
        log.info("getScores start imoNumber={} year={} month={}", imoNumber, year, month);

        if (month != null && year == null) {
            log.warn("Month without year rejected imoNumber={} month={}", imoNumber, month);
            throw new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified");
        }

        Optional<MtiScore> result;
        if (year == null) {
            result = repository.findLatest(imoNumber);
        } else if (month == null) {
            result = repository.findLatestByYear(imoNumber, year);
        } else {
            result = repository.findByYearAndMonth(imoNumber, year, month);
        }

        if (result.isEmpty()) {
            log.warn("No scores found imoNumber={} year={} month={}", imoNumber, year, month);
            throw new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber);
        }

        MtiScore score = result.get();
        MtiScoreDataDto dto = toDto(score);
        log.info("getScores success imoNumber={} year={} month={}", imoNumber, score.getYear(), score.getMonth());
        return dto;
    }

    private MtiScoreDataDto toDto(MtiScore score) {
        ScoresDto scoresDto = toScoresDto(score);
        MetadataDto metadataDto = new MetadataDto(
                score.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                score.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
        return new MtiScoreDataDto(
                score.getImoNumber(),
                score.getYear(),
                score.getMonth(),
                scoresDto,
                metadataDto
        );
    }

    private ScoresDto toScoresDto(MtiScore score) {
        return new ScoresDto(
                score.getMtiScore(),
                score.getVesselScore(),
                score.getReportingScore(),
                score.getVoyagesScore(),
                score.getEmissionsScore(),
                score.getSanctionsScore()
        );
    }
}
