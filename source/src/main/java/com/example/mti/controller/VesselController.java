package com.example.mti.controller;

import com.example.mti.dto.SuccessResponse;
import com.example.mti.filter.RequestIdFilter;
import com.example.mti.service.MtiScoresService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class VesselController {

    private static final Logger log = LoggerFactory.getLogger(VesselController.class);

    private final MtiScoresService mtiScoresService;

    public VesselController(MtiScoresService mtiScoresService) {
        this.mtiScoresService = mtiScoresService;
    }

    @GetMapping("/vessels/{imo}/mti-scores")
    public ResponseEntity<SuccessResponse> getMtiScores(
            @PathVariable String imo,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = "unknown";
        }
        log.info("GetMtiScores requestId={} imo={} year={} month={}", requestId, imo, year, month);
        SuccessResponse response = mtiScoresService.getScores(requestId, imo, year, month);
        return ResponseEntity.ok(response);
    }
}
