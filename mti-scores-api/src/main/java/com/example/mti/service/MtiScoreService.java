package com.example.mti.service;

import com.example.mti.dto.ScoreDataDto;
import com.example.mti.dto.ScoreMetadataDto;
import com.example.mti.dto.ScoresDto;
import com.example.mti.exception.InvalidParameterException;
import com.example.mti.exception.ResourceNotFoundException;
import com.example.mti.model.MtiScoreRecord;
import com.example.mti.repository.MtiScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class MtiScoreService {

    private static final Logger log = LoggerFactory.getLogger(MtiScoreService.class);
    private static final Pattern IMO_PATTERN = Pattern.compile("^[0-9]{7}$");

    private final MtiScoreRepository repository;

    public MtiScoreService(MtiScoreRepository repository) {
        this.repository = repository;
    }

    public ScoreDataDto getScores(String imoNumber, Integer year, Integer month) {
        // Step 1: IMO format check
        if (!IMO_PATTERN.matcher(imoNumber).matches()) {
            log.warn("Invalid IMO format imo={}", imoNumber);
            throw InvalidParameterException.invalidImoFormat(imoNumber);
        }

        // Step 2: Month without year check
        if (month != null && year == null) {
            log.warn("Month specified without year imo={}", imoNumber);
            throw InvalidParameterException.monthWithoutYear();
        }

        // Step 3: Year range check
        if (year != null && (year < 2000 || year > 2100)) {
            log.warn("Invalid year value year={}", year);
            throw InvalidParameterException.invalidDateRange("year=" + year);
        }

        // Step 4: Month range check
        if (month != null && (month < 1 || month > 12)) {
            log.warn("Invalid month value month={}", month);
            throw InvalidParameterException.invalidDateRange("month=" + month);
        }

        // Step 5: Log info
        log.info("Fetching MTI scores imo={} year={} month={}", imoNumber, year, month);

        // Step 6: Query repository
        Optional<MtiScoreRecord> result;
        if (year == null) {
            result = repository.findLatest(imoNumber);
        } else if (month == null) {
            result = repository.findLatestByYear(imoNumber, year);
        } else {
            result = repository.findByYearAndMonth(imoNumber, year, month);
        }

        // Step 7: Not found check
        if (result.isEmpty()) {
            log.warn("No MTI scores found imo={} year={} month={}", imoNumber, year, month);
            throw new ResourceNotFoundException(imoNumber);
        }

        // Step 8: Extract record
        MtiScoreRecord record = result.get();

        // Step 9: Build and return DTO
        return new ScoreDataDto(
                record.imoNumber(),
                record.year(),
                record.month(),
                new ScoresDto(
                        record.mtiScore(),
                        record.vesselScore(),
                        record.reportingScore(),
                        record.voyagesScore(),
                        record.emissionsScore(),
                        record.sanctionsScore()
                ),
                new ScoreMetadataDto(
                        record.createdAt().toString(),
                        record.updatedAt().toString()
                )
        );
    }
}
