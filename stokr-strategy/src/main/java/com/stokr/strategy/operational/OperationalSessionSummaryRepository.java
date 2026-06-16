package com.stokr.strategy.operational;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface OperationalSessionSummaryRepository extends JpaRepository<OperationalSessionSummary, Long> {

    Optional<OperationalSessionSummary> findBySessionDate(LocalDate sessionDate);
}
