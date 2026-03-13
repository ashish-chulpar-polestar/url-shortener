package com.example.mti.controller;

import com.example.mti.dto.ApiResponse;
import com.example.mti.dto.MetaDto;
import com.example.mti.dto.MtiScoreDataDto;
import com.example.mti.filter.RequestIdFilter;
import com.example.mti.service.MtiScoresService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1")
@Validated
public class MtiScoresController {

    private static final Logger log = LoggerFactory.getLogger(MtiScoresController.class);

    private final MtiScoresService service;

    public MtiScoresController(MtiScoresService service) {
        this.service = service;
    }

    @GetMapping("/vessels/{imo}/mti-scores")
    public ResponseEntity<ApiResponse<MtiScoreDataDto>> getMtiScores(
            @PathVariable
            @Pattern(regexp = "^[0-9]{7}$", message = "IMO number must be exactly 7 digits")
            String imo,

            @RequestParam(required = false)
            @Min(value = 2000, message = "Year must be >= 2000")
            @Max(value = 2100, message = "Year must be <= 2100")
            Integer year,

            @RequestParam(required = false)
            @Min(value = 1, message = "Month must be >= 1")
            @Max(value = 12, message = "Month must be <= 12")
            Integer month,

            HttpServletRequest request) {

        log.info("Request getMtiScores imo={} year={} month={}", imo, year, month);

        MtiScoreDataDto data = service.getScores(imo, year, month);

        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        String requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        MetaDto meta = new MetaDto(requestId, requestTimestamp);
        ApiResponse<MtiScoreDataDto> response = new ApiResponse<>(meta, data);

        log.info("Response getMtiScores imo={} status=200", imo);
        return ResponseEntity.ok(response);
    }
}
