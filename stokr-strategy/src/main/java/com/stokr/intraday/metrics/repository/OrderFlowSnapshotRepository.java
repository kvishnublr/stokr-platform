package com.stokr.intraday.metrics.repository;

import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderFlowSnapshotRepository extends JpaRepository<OrderFlowSnapshot, UUID> {

    /**
     * Find the most recent snapshot for a symbol
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.isValid = true ORDER BY o.timestamp DESC LIMIT 1")
    Optional<OrderFlowSnapshot> findLatestBySymbol(@Param("symbol") String symbol);

    /**
     * Find snapshots for a symbol within a time range
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.timestamp BETWEEN :startTime AND :endTime " +
           "AND o.isValid = true ORDER BY o.timestamp DESC")
    List<OrderFlowSnapshot> findBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Find snapshots with strong buyer pressure
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.buyerPressureScore > :minScore " +
           "AND o.isValid = true ORDER BY o.timestamp DESC LIMIT 10")
    List<OrderFlowSnapshot> findWithBuyerPressure(
        @Param("symbol") String symbol,
        @Param("minScore") Integer minScore
    );

    /**
     * Find snapshots with strong seller pressure
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.sellerPressureScore > :minScore " +
           "AND o.isValid = true ORDER BY o.timestamp DESC LIMIT 10")
    List<OrderFlowSnapshot> findWithSellerPressure(
        @Param("symbol") String symbol,
        @Param("minScore") Integer minScore
    );

    /**
     * Find snapshots with good liquidity
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.liquidityScore > :minScore " +
           "AND o.isValid = true ORDER BY o.timestamp DESC LIMIT 10")
    List<OrderFlowSnapshot> findWithGoodLiquidity(
        @Param("symbol") String symbol,
        @Param("minScore") Integer minScore
    );

    /**
     * Get average metrics for a symbol over time window
     */
    @Query("SELECT AVG(CAST(o.buyerPressureScore AS DOUBLE)), " +
           "AVG(CAST(o.liquidityScore AS DOUBLE)), " +
           "AVG(o.bidAskRatio) " +
           "FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.timestamp > :since AND o.isValid = true")
    Object[] getAverageMetrics(
        @Param("symbol") String symbol,
        @Param("since") Instant since
    );

    /**
     * Find latest snapshots for multiple symbols
     */
    @Query("SELECT o FROM OrderFlowSnapshot o WHERE o.symbol IN :symbols " +
           "AND o.isValid = true AND o.timestamp = " +
           "(SELECT MAX(o2.timestamp) FROM OrderFlowSnapshot o2 " +
           "WHERE o2.symbol = o.symbol AND o2.isValid = true)")
    List<OrderFlowSnapshot> findLatestForSymbols(@Param("symbols") List<String> symbols);

    /**
     * Paginated query for all snapshots of a symbol
     */
    Page<OrderFlowSnapshot> findBySymbolOrderByTimestampDesc(
        String symbol,
        Pageable pageable
    );

    /**
     * Check if data is stale (no updates in last N seconds)
     */
    @Query("SELECT COUNT(o) FROM OrderFlowSnapshot o WHERE o.symbol = :symbol " +
           "AND o.timestamp > :recentTime AND o.isValid = true")
    long countRecentSnapshots(
        @Param("symbol") String symbol,
        @Param("recentTime") Instant recentTime
    );

    /**
     * Find all valid snapshots created after a timestamp
     */
    List<OrderFlowSnapshot> findByTimestampAfterAndIsValidTrueOrderByTimestampDesc(Instant timestamp);

    /**
     * Count total valid snapshots for a symbol
     */
    long countBySymbolAndIsValidTrue(String symbol);

    /**
     * Find all distinct symbols with valid snapshots after a timestamp
     */
    @Query("SELECT DISTINCT o.symbol FROM OrderFlowSnapshot o WHERE o.isValid = true " +
           "AND o.timestamp > :since ORDER BY o.symbol")
    List<String> findDistinctSymbolsWithValidSnapshots(@Param("since") Instant since);

    /**
     * Count distinct symbols with valid recent snapshots
     */
    @Query("SELECT COUNT(DISTINCT o.symbol) FROM OrderFlowSnapshot o WHERE o.isValid = true " +
           "AND o.timestamp > :since")
    long countDistinctSymbolsWithValidSnapshots(@Param("since") Instant since);
}
