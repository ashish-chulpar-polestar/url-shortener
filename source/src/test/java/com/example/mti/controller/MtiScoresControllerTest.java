package com.example.mti.controller;

import com.example.mti.config.RateLimitConfig;
import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.MetadataDto;
import com.example.mti.dto.MtiScoreDataDto;
import com.example.mti.dto.ScoresDto;
import com.example.mti.exception.MtiException;
import com.example.mti.service.MtiScoresService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MtiScoresController.class)
@Import(RateLimitConfig.class)
class MtiScoresControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MtiScoresService service;

    private MtiScoreDataDto buildDto(String imo, int year, int month, BigDecimal mtiScore) {
        ScoresDto scores = new ScoresDto(mtiScore, new BigDecimal("90.00"),
                new BigDecimal("88.75"), new BigDecimal("82.30"),
                new BigDecimal("87.60"), new BigDecimal("100.00"));
        MetadataDto metadata = new MetadataDto("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        return new MtiScoreDataDto(imo, year, month, scores, metadata);
    }

    @Test
    void getMtiScores_noFilters_returns200() throws Exception {
        when(service.getScores("9123456", null, null))
                .thenReturn(buildDto("9123456", 2024, 1, new BigDecimal("85.50")));

        mockMvc.perform(get("/api/v1/vessels/9123456/mti-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imo_number").value("9123456"))
                .andExpect(jsonPath("$.data.scores.mti_score").value(85.50))
                .andExpect(jsonPath("$.meta.request_id").isNotEmpty());
    }

    @Test
    void getMtiScores_yearFilter_returns200() throws Exception {
        when(service.getScores("9123456", 2023, null))
                .thenReturn(buildDto("9123456", 2023, 12, new BigDecimal("80.00")));

        mockMvc.perform(get("/api/v1/vessels/9123456/mti-scores?year=2023"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(2023));
    }

    @Test
    void getMtiScores_yearAndMonthFilter_returns200() throws Exception {
        when(service.getScores("9123456", 2023, 6))
                .thenReturn(buildDto("9123456", 2023, 6, new BigDecimal("75.25")));

        mockMvc.perform(get("/api/v1/vessels/9123456/mti-scores?year=2023&month=6"))
                .andExpect(status().isOk());
    }

    @Test
    void getMtiScores_imoNotFound_returns404() throws Exception {
        when(service.getScores("9999999", null, null))
                .thenThrow(new MtiException(ErrorCode.ERR_101, "No MTI scores found for IMO 9999999"));

        mockMvc.perform(get("/api/v1/vessels/9999999/mti-scores"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.error_code").value("ERR_101"))
                .andExpect(jsonPath("$.data.title").value("Resource Not Found"));
    }

    @Test
    void getMtiScores_invalidImoFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/vessels/123/mti-scores"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error_code").value("ERR_103"));
    }

    @Test
    void getMtiScores_monthWithoutYear_returns400() throws Exception {
        when(service.getScores("9123456", null, 6))
                .thenThrow(new MtiException(ErrorCode.ERR_102, "Month parameter requires year parameter to be specified"));

        mockMvc.perform(get("/api/v1/vessels/9123456/mti-scores?month=6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error_code").value("ERR_102"));
    }

    @Test
    void getMtiScores_invalidMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/vessels/9123456/mti-scores?year=2023&month=13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error_code").value("ERR_104"));
    }

    @Test
    void getMtiScores_nullScores_returnsNullFields() throws Exception {
        ScoresDto scores = new ScoresDto(null, new BigDecimal("88.00"), null,
                new BigDecimal("80.00"), new BigDecimal("85.00"), new BigDecimal("100.00"));
        MetadataDto metadata = new MetadataDto("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        MtiScoreDataDto dto = new MtiScoreDataDto("9123457", 2024, 1, scores, metadata);

        when(service.getScores("9123457", null, null)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/vessels/9123457/mti-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores.mti_score").isEmpty());
    }
}
