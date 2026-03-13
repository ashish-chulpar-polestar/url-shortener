package com.example.mti.service;

import com.example.mti.constant.ErrorCode;
import com.example.mti.dto.ScoreDataDto;
import com.example.mti.exception.InvalidParameterException;
import com.example.mti.exception.ResourceNotFoundException;
import com.example.mti.model.MtiScoreRecord;
import com.example.mti.repository.MtiScoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MtiScoreServiceTest {

    @Mock
    MtiScoreRepository repository;

    @InjectMocks
    MtiScoreService service;

    private MtiScoreRecord buildRecord(String imoNumber, int year, int month,
                                       Double mtiScore, Double vesselScore, Double reportingScore,
                                       Double voyagesScore, Double emissionsScore, Double sanctionsScore) {
        return new MtiScoreRecord(
                1L, imoNumber, year, month,
                mtiScore, vesselScore, reportingScore, voyagesScore, emissionsScore, sanctionsScore,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z")
        );
    }

    @Test
    void getScores_latestScores_returnsScoreData() {
        when(repository.findLatest("9123456"))
                .thenReturn(Optional.of(buildRecord("9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00)));

        ScoreDataDto result = service.getScores("9123456", null, null);

        assertEquals("9123456", result.imoNumber());
        assertEquals(2024, result.year());
        assertEquals(1, result.month());
        assertEquals(85.50, result.scores().mtiScore(), 0.001);
    }

    @Test
    void getScores_specificYear_callsFindLatestByYear() {
        when(repository.findLatestByYear("9123456", 2023))
                .thenReturn(Optional.of(buildRecord("9123456", 2023, 12, 82.00, 87.00, 83.00, 79.00, 83.00, 97.00)));

        ScoreDataDto result = service.getScores("9123456", 2023, null);

        assertEquals(2023, result.year());
        assertEquals(12, result.month());
        verify(repository).findLatestByYear("9123456", 2023);
    }

    @Test
    void getScores_specificYearAndMonth_callsFindByYearAndMonth() {
        when(repository.findByYearAndMonth("9123456", 2023, 6))
                .thenReturn(Optional.of(buildRecord("9123456", 2023, 6, 80.00, 85.00, 82.00, 78.00, 81.00, 95.00)));

        ScoreDataDto result = service.getScores("9123456", 2023, 6);

        assertEquals(6, result.month());
    }

    @Test
    void getScores_imoNotFound_throwsResourceNotFoundException() {
        when(repository.findLatest("9999999")).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.getScores("9999999", null, null));

        assertTrue(ex.getMessage().contains("9999999"));
    }

    @Test
    void getScores_invalidImoFormat_throwsInvalidParameterException() {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> service.getScores("123", null, null));

        assertEquals(ErrorCode.ERR_103, ex.getErrorCode());
        verifyNoInteractions(repository);
    }

    @Test
    void getScores_monthWithoutYear_throwsInvalidParameterException() {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> service.getScores("9123456", null, 6));

        assertEquals(ErrorCode.ERR_102, ex.getErrorCode());
    }

    @Test
    void getScores_invalidMonthValue_throwsInvalidParameterException() {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> service.getScores("9123456", 2023, 13));

        assertEquals(ErrorCode.ERR_104, ex.getErrorCode());
    }

    @Test
    void getScores_partialNullScores_returnsNullFields() {
        when(repository.findLatest("9123456"))
                .thenReturn(Optional.of(buildRecord("9123456", 2022, 3, 75.00, null, 79.00, null, 80.00, 90.00)));

        ScoreDataDto result = service.getScores("9123456", null, null);

        assertNull(result.scores().vesselScore());
        assertNull(result.scores().voyagesScore());
        assertEquals(75.00, result.scores().mtiScore(), 0.001);
    }
}
