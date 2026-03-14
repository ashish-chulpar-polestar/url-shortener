package com.example.mti.service;

import com.example.mti.dto.SuccessResponse;
import com.example.mti.exception.ErrorCode;
import com.example.mti.exception.MtiApiException;
import com.example.mti.model.MtiScoreRecord;
import com.example.mti.repository.MtiScoresRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MtiScoresServiceTest {

    @Mock
    MtiScoresRepository repository;

    @InjectMocks
    MtiScoresService service;

    @Test
    void getScores_invalidImo_throwsERR103() {
        MtiApiException ex = assertThrows(MtiApiException.class,
                () -> service.getScores("req-1", "123", null, null));
        assertEquals(ErrorCode.ERR_103, ex.getErrorCode());
    }

    @Test
    void getScores_monthWithoutYear_throwsERR102() {
        MtiApiException ex = assertThrows(MtiApiException.class,
                () -> service.getScores("req-2", "9123456", null, 6));
        assertEquals(ErrorCode.ERR_102, ex.getErrorCode());
        assertEquals("Month parameter requires year parameter to be specified", ex.getDetailMessage());
    }

    @Test
    void getScores_invalidYear_throwsERR104() {
        MtiApiException ex = assertThrows(MtiApiException.class,
                () -> service.getScores("req-3", "9123456", 1999, null));
        assertEquals(ErrorCode.ERR_104, ex.getErrorCode());
    }

    @Test
    void getScores_invalidMonth_throwsERR104() {
        MtiApiException ex = assertThrows(MtiApiException.class,
                () -> service.getScores("req-4", "9123456", 2023, 13));
        assertEquals(ErrorCode.ERR_104, ex.getErrorCode());
    }

    @Test
    void getScores_imoNotFound_throwsERR101() {
        when(repository.findLatest("9999999")).thenReturn(Optional.empty());
        MtiApiException ex = assertThrows(MtiApiException.class,
                () -> service.getScores("req-5", "9999999", null, null));
        assertEquals(ErrorCode.ERR_101, ex.getErrorCode());
    }

    @Test
    void getScores_latest_returnsCorrectResponse() {
        MtiScoreRecord record = new MtiScoreRecord("9123456", 2024, 1, 85.50, 90.00, 88.75, 82.30, 87.60, 100.00,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(repository.findLatest("9123456")).thenReturn(Optional.of(record));

        SuccessResponse response = service.getScores("req-4", "9123456", null, null);

        assertEquals("9123456", response.data().imoNumber());
        assertEquals(2024, response.data().year());
        assertEquals(1, response.data().month());
        assertEquals(85.50, response.data().scores().mtiScore());
        assertEquals(100.00, response.data().scores().sanctionsScore());
        assertEquals("req-4", response.meta().requestId());
    }

    @Test
    void getScores_withYear_callsFindLatestByYear() {
        MtiScoreRecord record = new MtiScoreRecord("9123456", 2023, 12, 80.00, 85.00, 82.50, 78.00, 83.00, 100.00,
                Instant.now(), Instant.now());
        when(repository.findLatestByYear("9123456", 2023)).thenReturn(Optional.of(record));

        service.getScores("req-5", "9123456", 2023, null);

        verify(repository, times(1)).findLatestByYear("9123456", 2023);
        verify(repository, never()).findLatest(any());
    }

    @Test
    void getScores_withYearAndMonth_callsFindByYearAndMonth() {
        MtiScoreRecord record = new MtiScoreRecord("9123456", 2023, 6, 75.00, 80.00, 78.00, 72.00, 76.00, 95.00,
                Instant.now(), Instant.now());
        when(repository.findByYearAndMonth("9123456", 2023, 6)).thenReturn(Optional.of(record));

        service.getScores("req-6", "9123456", 2023, 6);

        verify(repository, times(1)).findByYearAndMonth("9123456", 2023, 6);
    }

    @Test
    void getScores_partialNullScores_returnsNullFields() {
        MtiScoreRecord record = new MtiScoreRecord("9999998", 2024, 3, null, 88.00, null, 79.00, null, 100.00,
                Instant.now(), Instant.now());
        when(repository.findLatest("9999998")).thenReturn(Optional.of(record));

        SuccessResponse response = service.getScores("req-7", "9999998", null, null);

        assertNull(response.data().scores().mtiScore());
        assertEquals(88.00, response.data().scores().vesselScore());
        assertNull(response.data().scores().reportingScore());
    }
}
