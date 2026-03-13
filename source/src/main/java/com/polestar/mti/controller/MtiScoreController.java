package com.polestar.mti.controller;

import com.polestar.mti.dto.ApiResponse;
import com.polestar.mti.dto.MetaDto;
import com.polestar.mti.dto.MtiScoreDataDto;
import com.polestar.mti.filter.RequestIdFilter;
import com.polestar.mti.service.MtiScoreService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
public class MtiScoreController {

    private static final Logger log = LoggerFactory.getLogger(MtiScoreController.class);

    private final MtiScoreService mtiScoreService;

    public MtiScoreController(MtiScoreService mtiScoreService) {
        this.mtiScoreService = mtiScoreService;
    }

    @GetMapping("/api/v1/vessels/{imo}/mti-scores")
    public ResponseEntity<ApiResponse<MtiScoreDataDto>> getMtiScores(
            @PathVariable @Pattern(regexp = "^[0-9]{7}$", message = "IMO number must be exactly 7 digits") String imo,
            @RequestParam(required = false) @Min(value = 2000, message = "Year must be >= 2000") @Max(value = 2100, message = "Year must be <= 2100") Integer year,
            @RequestParam(required = false) @Min(value = 1, message = "Month must be >= 1") @Max(value = 12, message = "Month must be <= 12") Integer month,
            HttpServletRequest request) {

        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        log.info("getMtiScores imo={} year={} month={} requestId={}", imo, year, month, requestId);

        MtiScoreDataDto data = mtiScoreService.getScores(imo, year, month);
        MetaDto meta = MetaDto.of(requestId);
        return ResponseEntity.ok(new ApiResponse<>(meta, data));
    }
}
