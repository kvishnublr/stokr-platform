package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.BrokerPositionObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BrokerPositionObservationRepository extends JpaRepository<BrokerPositionObservation, UUID> {

    List<BrokerPositionObservation> findByUserIdAndSymbolOrderByObservationTimeDesc(UUID userId, String symbol);

    List<BrokerPositionObservation> findByUserIdOrderByObservationTimeDesc(UUID userId);

    @Query("SELECT b FROM BrokerPositionObservation b WHERE b.userId = ?1 AND b.symbol = ?2 " +
           "AND b.observationTime >= ?3 AND b.observationTime <= ?4 ORDER BY b.observationTime ASC")
    List<BrokerPositionObservation> findPositionHistory(UUID userId, String symbol, Instant from, Instant to);

    List<BrokerPositionObservation> findByIsOrphanedAndObservationTimeOrderByObservationTimeDesc(
        Boolean isOrphaned, Instant time);
}
