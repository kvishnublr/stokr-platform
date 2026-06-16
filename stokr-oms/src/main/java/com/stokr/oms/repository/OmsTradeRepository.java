package com.stokr.oms.repository;

import com.stokr.oms.domain.OmsTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OmsTradeRepository extends JpaRepository<OmsTrade, UUID>, JpaSpecificationExecutor<OmsTrade> {

    List<OmsTrade> findByOrder_IdOrderByCreatedAtAsc(UUID orderId);

    @Query("select t from OmsTrade t join fetch t.order o where o.userId = :userId and o.deleted = false order by t.createdAt asc")
    List<OmsTrade> findAllForUserOrdered(@Param("userId") UUID userId);

    @Query("select t from OmsTrade t join fetch t.order o where o.userId = :userId and o.symbol = :symbol and o.deleted = false order by t.createdAt asc")
    List<OmsTrade> findAllForUserAndSymbolOrdered(@Param("userId") UUID userId, @Param("symbol") String symbol);

    @Query("""
            select coalesce(o.brokerVendor, 'UNKNOWN'), sum(t.quantity * t.price)
            from OmsTrade t join t.order o
            where o.userId = :userId and t.deleted = false and o.deleted = false
            group by coalesce(o.brokerVendor, 'UNKNOWN')
            """)
    List<Object[]> sumNotionalByBrokerVendor(@Param("userId") UUID userId);

    @Query("""
            select t from OmsTrade t join fetch t.order o
            where o.userId = :userId and t.deleted = false and o.deleted = false
            and t.createdAt >= :startTime and t.createdAt < :endTime
            order by t.createdAt asc
            """)
    List<OmsTrade> findByOrderUserIdAndCreatedAtBetweenAndDeletedFalse(
            @Param("userId") UUID userId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );
}
