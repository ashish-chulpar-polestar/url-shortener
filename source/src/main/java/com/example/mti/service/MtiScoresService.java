package com.example.mti.service;

import com.example.mti.dto.*;
import com.example.mti.exception.ErrorCode;
import com.example.mti.exception.MtiApiException;
import com.example.mti.model.MtiScoreRecord;
import com.example.mti.repository.MtiScoresRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class MtiScoresService {

    private static final Logger log = LoggerFactory.getLogger(MtiScoresService.class);
    private static final Pattern IMO_PATTERN = Pattern.compile("^[0-9]{7}$");

    private final MtiScoresRepository mtiScoresRepository;

    public MtiScoresService(MtiScoresRepository mtiScoresRepository) {
        this.mtiScoresRepository = mtiScoresRepository;
    }

    public SuccessResponse getScores(String requestId, String imoNumber, Integer year, Integer month) {
        // Step 1 — Validate IMO
        if (!IMO_PATTERN.matcher(imoNumber).matches()) {
            log.warn("Invalid IMO format imo={} requestId={}", imoNumber, requestId);
            throw new MtiApiException(ErrorCode.ERR_103, "IMO number must be 7 digits");
        }

        // Step 2 — Validate month-without-year
        if (month != null && year == null) {
            log.warn("Month without year requestId={}", requestId);
            throw new MtiApiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified");
        }

        // Step 3 — Validate ranges
        if (year != null && (year < 2000 || year > 2100)) {
            throw new MtiApiException(ErrorCode.ERR_104, "Year must be between 2000 and 2100");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new MtiApiException(ErrorCode.ERR_104, "Month must be between 1 and 12");
        }

        // Step 4 — Log entry
        log.info("GetScores requestId={} imo={} year={} month={}", requestId, imoNumber, year, month);

        // Step 5 — Route
        Optional<MtiScoreRecord> result;
        if (year == null) {
            result = mtiScoresRepository.findLatest(imoNumber);
        } else if (month == null) {
            result = mtiScoresRepository.findLatestByYear(imoNumber, year);
        } else {
            result = mtiScoresRepository.findByYearAndMonth(imoNumber, year, month);
        }

        // Step 6 — Not found
        if (result.isEmpty()) {
            log.warn("No MTI scores found imo={} year={} month={} requestId={}", imoNumber, year, month, requestId);
            throw new MtiApiException(ErrorCode.ERR_101, "No MTI scores found for IMO " + imoNumber);
        }

        // Step 7 — Map to response
        MtiScoreRecord record = result.get();
        ScoresDto scores = new ScoresDto(
                record.mtiScore(),
                record.vesselScore(),
                record.reportingScore(),
                record.voyagesScore(),
                record.emissionsScore(),
                record.sanctionsScore()
        );
        MtiScoresMetadata metadata = new MtiScoresMetadata(
                record.createdAt() != null ? record.createdAt().toString() : null,
                record.updatedAt() != null ? record.updatedAt().toString() : null
        );
        MtiScoresData data = new MtiScoresData(record.imoNumber(), record.year(), record.month(), scores, metadata);
        MetaDto meta = new MetaDto(requestId, Instant.now().toString());
        return new SuccessResponse(meta, data);
    }
}
