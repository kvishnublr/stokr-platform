package com.stokr.execution.capital;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyCapitalReservationRepository extends JpaRepository<StrategyCapitalReservation, UUID> {

    List<StrategyCapitalReservation> findByStrategyKeyAndStatusAndDeletedFalse(String strategyKey, String status);

    Optional<StrategyCapitalReservation> findByOrderIdAndDeletedFalse(UUID orderId);

    Optional<StrategyCapitalReservation> findBySignalIdAndStatusAndDeletedFalse(UUID signalId, String status);

    @Query("""
            select coalesce(sum(r.reservedAmount), 0) from StrategyCapitalReservation r
            where r.strategyKey = :strategyKey and r.status = 'ACTIVE' and r.deleted = false
            and (r.orderId is null or exists (
                select 1 from OmsOrder o
                where o.id = r.orderId and o.deleted = false and o.simulation = false
            ))
            """)
    BigDecimal sumActiveReserved(@Param("strategyKey") String strategyKey);

    long countByStrategyKeyAndStatusAndDeletedFalse(String strategyKey, String status);
}
