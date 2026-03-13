package com.polestar.mti.service;

import com.polestar.mti.constant.ErrorCode;
import com.polestar.mti.dto.MtiScoreDataDto;
import com.polestar.mti.entity.MtiScore;
import com.polestar.mti.exception.MtiException;
import com.polestar.mti.repository.MtiScoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MtiScoreServiceTest {

    @Mock
    private MtiScoreRepository mtiScoreRepository;

    @InjectMocks
    private MtiScoreService mtiScoreService;

    private MtiScore buildEntity() {
        MtiScore entity = new MtiScore();
        entity.setImoNumber("9123456");
        entity.setYear(2024);
        entity.setMonth(1);
        entity.setMtiScore(new BigDecimal("85.50"));
        entity.setVesselScore(new BigDecimal("90.00"));
        entity.setReportingScore(new BigDecimal("88.75"));
        entity.setVoyagesScore(new BigDecimal("82.30"));
        entity.setEmissionsScore(new BigDecimal("87.60"));
        entity.setSanctionsScore(new BigDecimal("100.00"));
        entity.setCreatedAt(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        entity.setUpdatedAt(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        return entity;
    }

    @Test
    void getScores_latest_returnsDto() {
        when(mtiScoreRepository.findTopByImoNumberOrderByYearDescMonthDesc("9123456"))
                .thenReturn(Optional.of(buildEntity()));

        MtiScoreDataDto result = mtiScoreService.getScores("9123456", null, null);

        assertThat(result.getImoNumber()).isEqualTo("9123456");
        assertThat(result.getYear()).isEqualTo(2024);
        assertThat(result.getMonth()).isEqualTo(1);
        assertThat(result.getScores().getMtiScore()).isEqualTo(new BigDecimal("85.50"));
    }

    @Test
    void getScores_yearOnly_returnsDto() {
        when(mtiScoreRepository.findTopByImoNumberAndYearOrderByMonthDesc("9123456", 2023))
                .thenReturn(Optional.of(buildEntity()));

        MtiScoreDataDto result = mtiScoreService.getScores("9123456", 2023, null);

        assertThat(result.getImoNumber()).isEqualTo("9123456");
    }

    @Test
    void getScores_yearAndMonth_returnsDto() {
        when(mtiScoreRepository.findByImoNumberAndYearAndMonth("9123456", 2023, 6))
                .thenReturn(Optional.of(buildEntity()));

        MtiScoreDataDto result = mtiScoreService.getScores("9123456", 2023, 6);

        assertThat(result.getImoNumber()).isEqualTo("9123456");
    }

    @Test
    void getScores_monthWithoutYear_throwsErr102() {
        assertThatThrownBy(() -> mtiScoreService.getScores("9123456", null, 6))
                .isInstanceOf(MtiException.class)
                .satisfies(ex -> assertThat(((MtiException) ex).getErrorCode()).isEqualTo(ErrorCode.ERR_102));
    }

    @Test
    void getScores_notFound_throwsErr101() {
        when(mtiScoreRepository.findTopByImoNumberOrderByYearDescMonthDesc("9999999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> mtiScoreService.getScores("9999999", null, null))
                .isInstanceOf(MtiException.class)
                .satisfies(ex -> assertThat(((MtiException) ex).getErrorCode()).isEqualTo(ErrorCode.ERR_101));
    }

    @Test
    void getScores_nullScores_returnedAsNull() {
        MtiScore entity = buildEntity();
        entity.setMtiScore(null);
        when(mtiScoreRepository.findTopByImoNumberOrderByYearDescMonthDesc("9123456"))
                .thenReturn(Optional.of(entity));

        MtiScoreDataDto result = mtiScoreService.getScores("9123456", null, null);

        assertThat(result.getScores().getMtiScore()).isNull();
    }
}
