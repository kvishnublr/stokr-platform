package com.stokr.oms.repository;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OmsOrderRepository extends JpaRepository<OmsOrder, UUID>, JpaSpecificationExecutor<OmsOrder> {

    long countByDeletedFalse();

    long countByDeletedFalseAndState(OrderState state);

    long countByDeletedFalseAndCreatedAtGreaterThanEqual(@Param("since") Instant since);

    @Query("""
            select count(o) from OmsOrder o
            where o.deleted = false
            and o.state in :states
            and o.updatedAt < :before
            """)
    long countStuckOrders(@Param("states") Collection<OrderState> states, @Param("before") Instant before);

    Optional<OmsOrder> findByUserIdAndIdempotencyKeyAndDeletedFalse(UUID userId, String idempotencyKey);

    long countByUserIdAndDeletedFalse(UUID userId);

    long countByUserIdAndDeletedFalseAndState(UUID userId, OrderState state);

    long countByUserIdAndDeletedFalseAndBacktestRunIdIsNullAndStateIn(UUID userId, Collection<OrderState> states);

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.deleted = false and o.backtestRunId is null
            and o.createdAt >= :start and o.createdAt < :end
            """)
    long countByUserAndDayNonBacktest(@Param("userId") UUID userId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select max(o.createdAt) from OmsOrder o
            where o.userId = :userId and o.symbol = :symbol and o.deleted = false
            and (:excludeId is null or o.id <> :excludeId)
            """)
    Optional<Instant> findLatestCreatedAtForUserSymbolExcluding(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol,
            @Param("excludeId") UUID excludeId
    );

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.deleted = false and o.backtestRunId is null
            and o.createdAt >= :since and o.id <> :excludeId
            """)
    long countNonBacktestOrdersSinceExcluding(
            @Param("userId") UUID userId,
            @Param("since") Instant since,
            @Param("excludeId") UUID excludeId
    );

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.symbol = :symbol and o.side = :side and o.deleted = false
            and o.backtestRunId is null and o.id <> :excludeId and o.state in :states
            """)
    long countActiveSameDirection(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol,
            @Param("side") String side,
            @Param("excludeId") UUID excludeId,
            @Param("states") Collection<OrderState> states
    );

    @Query("""
            select coalesce(sum(o.quantity * coalesce(o.limitPrice, o.entryReferencePrice, 0)), 0)
            from OmsOrder o
            where o.userId = :userId and o.strategyKey = :strategyKey and o.deleted = false
            and o.backtestRunId is null and o.id <> :excludeId and o.state in :states
            """)
    BigDecimal sumOpenNotionalExcluding(
            @Param("userId") UUID userId,
            @Param("strategyKey") String strategyKey,
            @Param("excludeId") UUID excludeId,
            @Param("states") Collection<OrderState> states
    );

    @Query("""
            select o from OmsOrder o
            where o.deleted = false and o.state in :states
            order by o.updatedAt desc
            """)
    List<OmsOrder> findRecentByStateIn(@Param("states") Collection<OrderState> states, Pageable pageable);
}
