package com.stokr.intraday.repository;

import com.stokr.intraday.domain.UserTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for user intraday trades
 * Used for tracking performance and validating probability calculations
 */
@Repository
public interface UserTradeRepository extends JpaRepository<UserTrade, Long> {

    /**
     * Get all trades for a user
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId ORDER BY ut.entryTime DESC")
    List<UserTrade> findByUserId(@Param("userId") Long userId);

    /**
     * Get today's trades for a user
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND DATE(ut.entryTime) = CURRENT_DATE " +
            "ORDER BY ut.entryTime DESC")
    List<UserTrade> findTodaysTrades(@Param("userId") Long userId);

    /**
     * Get closed trades for a user in a date range
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.status IN ('CLOSED', 'STOPPED_OUT') " +
            "AND ut.exitTime >= :fromTime AND ut.exitTime <= :toTime " +
            "ORDER BY ut.exitTime DESC")
    List<UserTrade> findClosedTradesBetween(
            @Param("userId") Long userId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    /**
     * Get trades of a specific setup type
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.setupType = :setupType " +
            "AND ut.status IN ('CLOSED', 'STOPPED_OUT') ORDER BY ut.entryTime DESC")
    List<UserTrade> findByUserIdAndSetupType(
            @Param("userId") Long userId,
            @Param("setupType") String setupType);

    /**
     * Get trades in a specific stock
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.stockId = :stockId " +
            "ORDER BY ut.entryTime DESC")
    List<UserTrade> findByUserIdAndStock(
            @Param("userId") Long userId,
            @Param("stockId") String stockId);

    /**
     * Get open positions for a user
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.status = 'OPEN' " +
            "ORDER BY ut.entryTime DESC")
    List<UserTrade> findOpenPositions(@Param("userId") Long userId);

    /**
     * Get winning trades in a time period
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.result = 'WIN' " +
            "AND ut.exitTime >= :fromTime AND ut.exitTime <= :toTime " +
            "ORDER BY ut.exitTime DESC")
    List<UserTrade> findWinsByPeriod(
            @Param("userId") Long userId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    /**
     * Get losing trades in a time period
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId AND ut.result = 'LOSS' " +
            "AND ut.exitTime >= :fromTime AND ut.exitTime <= :toTime " +
            "ORDER BY ut.profitLoss ASC")
    List<UserTrade> findLossesByPeriod(
            @Param("userId") Long userId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    /**
     * Get trades in a specific market regime
     */
    @Query("SELECT ut FROM UserTrade ut WHERE ut.userId = :userId " +
            "AND ut.marketRegimeAtEntry = :regime AND ut.status IN ('CLOSED', 'STOPPED_OUT') " +
            "ORDER BY ut.entryTime DESC")
    List<UserTrade> findByMarketRegime(
            @Param("userId") Long userId,
            @Param("regime") String regime);

    /**
     * Count total trades for a user
     */
    @Query("SELECT COUNT(ut) FROM UserTrade ut WHERE ut.userId = :userId " +
            "AND ut.status IN ('CLOSED', 'STOPPED_OUT')")
    long countCompletedTrades(@Param("userId") Long userId);

    /**
     * Count winning trades for a user
     */
    @Query("SELECT COUNT(ut) FROM UserTrade ut WHERE ut.userId = :userId AND ut.result = 'WIN'")
    long countWins(@Param("userId") Long userId);

    /**
     * Calculate total profit for a user
     */
    @Query("SELECT SUM(ut.profitLoss) FROM UserTrade ut WHERE ut.userId = :userId " +
            "AND ut.status IN ('CLOSED', 'STOPPED_OUT')")
    java.math.BigDecimal calculateTotalProfit(@Param("userId") Long userId);

    /**
     * Get average win size
     */
    @Query("SELECT AVG(ut.profitLossPercent) FROM UserTrade ut WHERE ut.userId = :userId " +
            "AND ut.result = 'WIN'")
    java.math.BigDecimal calculateAverageWinPercent(@Param("userId") Long userId);

    /**
     * Get average loss size
     */
    @Query("SELECT AVG(ut.profitLossPercent) FROM UserTrade ut WHERE ut.userId = :userId " +
            "AND ut.result = 'LOSS'")
    java.math.BigDecimal calculateAverageLossPercent(@Param("userId") Long userId);
}
