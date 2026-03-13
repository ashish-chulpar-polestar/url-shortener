package com.example.mti.controller;

import com.example.mti.dto.MetaDto;
import com.example.mti.dto.ScoreDataDto;
import com.example.mti.dto.SuccessResponseDto;
import com.example.mti.filter.RequestIdFilter;
import com.example.mti.service.MtiScoreService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vessels")
public class MtiScoreController {

    private static final Logger log = LoggerFactory.getLogger(MtiScoreController.class);

    private final MtiScoreService mtiScoreService;

    public MtiScoreController(MtiScoreService mtiScoreService) {
        this.mtiScoreService = mtiScoreService;
    }

    @GetMapping("/{imo}/mti-scores")
    public ResponseEntity<SuccessResponseDto> getMtiScores(
            @PathVariable String imo,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletRequest httpRequest) {

        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        String requestTimestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        log.info("GET mti-scores imo={} year={} month={} requestId={}", imo, year, month, requestId);

        ScoreDataDto data = mtiScoreService.getScores(imo, year, month);
        MetaDto meta = new MetaDto(requestId, requestTimestamp);

        return ResponseEntity.ok(new SuccessResponseDto(meta, data));
    }
}
