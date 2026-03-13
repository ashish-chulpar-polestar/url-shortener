package com.polestar.mti.service;

import com.polestar.mti.constant.ErrorCode;
import com.polestar.mti.dto.MetadataDto;
import com.polestar.mti.dto.MtiScoreDataDto;
import com.polestar.mti.dto.ScoresDto;
import com.polestar.mti.entity.MtiScore;
import com.polestar.mti.exception.MtiException;
import com.polestar.mti.repository.MtiScoreRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MtiScoreService {

    private static final Logger log = LoggerFactory.getLogger(MtiScoreService.class);

    private final MtiScoreRepository mtiScoreRepository;

    public MtiScoreDataDto getScores(String imoNumber, Integer year, Integer month) {
        if (month != null && year == null) {
            log.warn("Month specified without year imo={}", imoNumber);
            throw new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified");
        }

        Optional<MtiScore> result;
        if (year != null && month != null) {
            log.info("Fetching scores imo={} year={} month={}", imoNumber, year, month);
            result = mtiScoreRepository.findByImoNumberAndYearAndMonth(imoNumber, year, month);
        } else if (year != null) {
            log.info("Fetching latest scores imo={} year={}", imoNumber, year);
            result = mtiScoreRepository.findTopByImoNumberAndYearOrderByMonthDesc(imoNumber, year);
        } else {
            log.info("Fetching latest scores imo={}", imoNumber);
            result = mtiScoreRepository.findTopByImoNumberOrderByYearDescMonthDesc(imoNumber);
        }

        if (result.isEmpty()) {
            log.warn("No scores found imo={} year={} month={}", imoNumber, year, month);
            throw new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber);
        }

        MtiScore entity = result.get();
        ScoresDto scoresDto = new ScoresDto(
                entity.getMtiScore(),
                entity.getVesselScore(),
                entity.getReportingScore(),
                entity.getVoyagesScore(),
                entity.getEmissionsScore(),
                entity.getSanctionsScore()
        );
        MetadataDto metadataDto = new MetadataDto(
                entity.getCreatedAt().toInstant().toString(),
                entity.getUpdatedAt().toInstant().toString()
        );
        return new MtiScoreDataDto(
                entity.getImoNumber(),
                entity.getYear(),
                entity.getMonth(),
                scoresDto,
                metadataDto
        );
    }
}
