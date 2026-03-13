package com.example.mti.controller;

import com.example.mti.config.RateLimitConfig;
import com.example.mti.dto.ScoreDataDto;
import com.example.mti.dto.ScoreMetadataDto;
import com.example.mti.dto.ScoresDto;
import com.example.mti.exception.GlobalExceptionHandler;
import com.example.mti.exception.InvalidParameterException;
import com.example.mti.exception.ResourceNotFoundException;
import com.example.mti.service.MtiScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({MtiScoreController.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MtiScoreControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    MtiScoreService mtiScoreService;

    @MockBean
    RateLimitConfig.RateLimitProperties rateLimitProperties;

    @Test
    void getMtiScores_validRequest_returns200() throws Exception {
        when(mtiScoreService.getScores("9123456", null, null))
                .thenReturn(new ScoreDataDto("9123456", 2024, 1,
                        new ScoresDto(85.50, 90.00, 88.75, 82.30, 87.60, 100.00),
                        new ScoreMetadataDto("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/9123456/mti-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.request_id").isNotEmpty())
                .andExpect(jsonPath("$.data.imo_number").value("9123456"))
                .andExpect(jsonPath("$.data.year").value(2024))
                .andExpect(jsonPath("$.data.scores.mti_score").value(85.5));
    }

    @Test
    void getMtiScores_imoNotFound_returns404() throws Exception {
        when(mtiScoreService.getScores("9999999", null, null))
                .thenThrow(new ResourceNotFoundException("9999999"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/9999999/mti-scores"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.error_code").value("ERR_101"))
                .andExpect(jsonPath("$.meta.request_id").isNotEmpty());
    }

    @Test
    void getMtiScores_monthWithoutYear_returns400() throws Exception {
        when(mtiScoreService.getScores(eq("9123456"), eq(null), eq(6)))
                .thenThrow(InvalidParameterException.monthWithoutYear());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/9123456/mti-scores?month=6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error_code").value("ERR_102"));
    }

    @Test
    void getMtiScores_invalidImo_returns400() throws Exception {
        when(mtiScoreService.getScores(eq("123"), eq(null), eq(null)))
                .thenThrow(InvalidParameterException.invalidImoFormat("123"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/123/mti-scores"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error_code").value("ERR_103"));
    }

    @Test
    void getMtiScores_internalError_returns500() throws Exception {
        when(mtiScoreService.getScores(eq("9123456"), eq(null), eq(null)))
                .thenThrow(new RuntimeException("DB down"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vessels/9123456/mti-scores"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.error_code").value("ERR_105"));
    }
}
