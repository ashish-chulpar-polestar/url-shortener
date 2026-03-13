package com.polestar.mti.repository;

import com.polestar.mti.entity.MtiScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MtiScoreRepository extends JpaRepository<MtiScore, Long> {

    Optional<MtiScore> findTopByImoNumberOrderByYearDescMonthDesc(String imoNumber);

    Optional<MtiScore> findTopByImoNumberAndYearOrderByMonthDesc(String imoNumber, Integer year);

    Optional<MtiScore> findByImoNumberAndYearAndMonth(String imoNumber, Integer year, Integer month);
}
