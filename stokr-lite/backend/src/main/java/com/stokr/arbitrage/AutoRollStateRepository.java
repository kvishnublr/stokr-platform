package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AutoRollStateRepository extends JpaRepository<AutoRollState, Long> {

    Optional<AutoRollState> findByCurrentPositionIdAndStatus(Long currentPositionId, String status);

    List<AutoRollState> findByStatus(String status);

    @Query("SELECT s FROM AutoRollState s WHERE s.currentPositionId = :positionId AND s.status IN ('ACTIVE')")
    Optional<AutoRollState> findActiveByCurrentPositionId(@Param("positionId") Long positionId);
}
