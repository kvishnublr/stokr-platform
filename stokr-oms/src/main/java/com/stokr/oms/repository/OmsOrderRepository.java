package com.stokr.oms.repository;

import com.stokr.oms.domain.OmsOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OmsOrderRepository extends JpaRepository<OmsOrder, UUID>, JpaSpecificationExecutor<OmsOrder> {

    Optional<OmsOrder> findByUserIdAndIdempotencyKeyAndDeletedFalse(UUID userId, String idempotencyKey);

    long countByUserIdAndDeletedFalse(UUID userId);

    @Query("select count(o) from OmsOrder o where o.userId = :userId and o.deleted = false and o.createdAt >= :start and o.createdAt < :end")
    long countByUserAndDay(@Param("userId") UUID userId, @Param("start") Instant start, @Param("end") Instant end);

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
}
