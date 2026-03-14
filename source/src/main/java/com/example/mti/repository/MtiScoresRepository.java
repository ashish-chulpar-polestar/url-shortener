package com.example.mti.repository;

import com.example.mti.model.MtiScoreRecord;

import java.util.Optional;

public interface MtiScoresRepository {

    Optional<MtiScoreRecord> findLatest(String imoNumber);

    Optional<MtiScoreRecord> findLatestByYear(String imoNumber, int year);

    Optional<MtiScoreRecord> findByYearAndMonth(String imoNumber, int year, int month);
}
