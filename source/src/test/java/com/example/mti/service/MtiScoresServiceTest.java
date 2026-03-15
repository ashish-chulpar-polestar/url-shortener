package com.example.mti.service;

import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.MtiScoreDataDto;
import com.example.mti.exception.MtiException;
import com.example.mti.model.MtiScore;
import com.example.mti.repository.MtiScoresRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MtiScoresServiceTest {

    @Mock
    private MtiScoresRepository repository;

    @InjectMocks
    private MtiScoresService service;

    private MtiScore buildMtiScore(String imo, int year, int month) {
        return new MtiScore(
                1L, imo, year, month,
                new BigDecimal("85.50"),
                new BigDecimal("90.00"),
                new BigDecimal("88.75"),
                new BigDecimal("82.30"),
                new BigDecimal("87.60"),
                new BigDecimal("100.00"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z")
        );
    }

    @Test
    void getScores_noFilters_callsFindLatest() {
        when(repository.findLatest("9123456")).thenReturn(Optional.of(buildMtiScore("9123456", 2024, 1)));

        MtiScoreDataDto result = service.getScores("9123456", null, null);

        assertThat(result.getImoNumber()).isEqualTo("9123456");
        assertThat(result.getYear()).isEqualTo(2024);
        assertThat(result.getMonth()).isEqualTo(1);
        assertThat(result.getScores().getMtiScore()).isEqualByComparingTo(new BigDecimal("85.50"));
        verify(repository).findLatest("9123456");
    }

    @Test
    void getScores_yearOnly_callsFindLatestByYear() {
        when(repository.findLatestByYear("9123456", 2023)).thenReturn(Optional.of(buildMtiScore("9123456", 2023, 12)));

        MtiScoreDataDto result = service.getScores("9123456", 2023, null);

        assertThat(result.getYear()).isEqualTo(2023);
        assertThat(result.getMonth()).isEqualTo(12);
    }

    @Test
    void getScores_yearAndMonth_callsFindByYearAndMonth() {
        when(repository.findByYearAndMonth("9123456", 2023, 6)).thenReturn(Optional.of(buildMtiScore("9123456", 2023, 6)));

        MtiScoreDataDto result = service.getScores("9123456", 2023, 6);

        assertThat(result.getMonth()).isEqualTo(6);
    }

    @Test
    void getScores_notFound_throwsERR101() {
        when(repository.findLatest("9999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getScores("9999999", null, null))
                .isInstanceOf(MtiException.class)
                .satisfies(ex -> assertThat(((MtiException) ex).getErrorCode()).isEqualTo(ErrorCode.ERR_101));
    }

    @Test
    void getScores_monthWithoutYear_throwsERR102() {
        assertThatThrownBy(() -> service.getScores("9123456", null, 6))
                .isInstanceOf(MtiException.class)
                .satisfies(ex -> assertThat(((MtiException) ex).getErrorCode()).isEqualTo(ErrorCode.ERR_102));
    }

    @Test
    void getScores_nullScoreFields_mappedAsNull() {
        MtiScore scoreWithNulls = new MtiScore(
                2L, "9123457", 2024, 1,
                null,
                new BigDecimal("88.00"),
                null,
                new BigDecimal("80.00"),
                new BigDecimal("85.00"),
                new BigDecimal("100.00"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z")
        );
        when(repository.findLatest("9123457")).thenReturn(Optional.of(scoreWithNulls));

        MtiScoreDataDto result = service.getScores("9123457", null, null);

        assertThat(result.getScores().getMtiScore()).isNull();
        assertThat(result.getScores().getReportingScore()).isNull();
    }
}
