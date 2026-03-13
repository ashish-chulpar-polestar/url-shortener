package com.polestar.mti.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "mti_scores_history")
@Getter
@Setter
public class MtiScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imo_number", nullable = false, length = 7)
    private String imoNumber;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "mti_score")
    private BigDecimal mtiScore;

    @Column(name = "vessel_score")
    private BigDecimal vesselScore;

    @Column(name = "reporting_score")
    private BigDecimal reportingScore;

    @Column(name = "voyages_score")
    private BigDecimal voyagesScore;

    @Column(name = "emissions_score")
    private BigDecimal emissionsScore;

    @Column(name = "sanctions_score")
    private BigDecimal sanctionsScore;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
