package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * INDEX HUNT signal for NIFTY50 / BANKNIFTY options trading
 * Represents detected 5-minute momentum trading opportunity on indices
 *
 * Direction: "CE" (call, bullish) or "PE" (put, bearish)
 * Signal Quality: 0-100 (composite score from momentum, trend, PCR, VIX)
 * State: PENDING (awaiting execution) → FILLED (executed) → CLOSED (T1/SL hit)
 */
@Entity
@Table(name = "index_signals", indexes = {
        @Index(name = "idx_index_signals_active", columnList = "is_active, time_detected"),
        @Index(name = "idx_index_signals_quality", columnList = "quality_score, time_detected"),
        @Index(name = "idx_index_signals_index", columnList = "index_name, direction, is_active"),
        @Index(name = "idx_index_signals_expiry", columnList = "expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexSignal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long signalId;

    // Core Identity
    @Column(nullable = false, length = 20)
    private String indexName;  // "NIFTY" or "BANKNIFTY"

    @Column(nullable = false, length = 5)
    private String direction;  // "CE" (call/bullish) or "PE" (put/bearish)

    @Column(nullable = false)
    private Instant timeDetected;

    // Price & Entry Details
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal indexPrice;  // NIFTY/BANKNIFTY spot price at signal time

    @Column(precision = 10, scale = 2)
    private BigDecimal optionEntryPremium;  // Option LTP at signal time (filled at execution)

    @Column(precision = 10, scale = 2)
    private BigDecimal optionSL;  // SL level = entry × 0.80

    @Column(precision = 10, scale = 2)
    private BigDecimal optionT1;  // T1 level = entry × 1.28 (target to hit 70%)

    @Column(precision = 10, scale = 2)
    private BigDecimal optionT2;  // T2 level = entry × 1.65 (rarely hit)

    // Gate Analysis (5 Gates)
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal momentum5m;  // 5-min price change %

    @Column(precision = 5, scale = 4)
    private BigDecimal trend30m;  // 30-min trend direction (positive = up, negative = down)

    @Column(precision = 5, scale = 2)
    private BigDecimal pcrRatio;  // Put/Call OI ratio (smart money indicator)

    @Column(precision = 5, scale = 2)
    private BigDecimal vixLevel;  // India VIX at signal time

    @Column(precision = 5, scale = 4)
    private BigDecimal sessionOpenPrice;  // Day open for session-lock gate

    @Column(precision = 5, scale = 4)
    private BigDecimal recent3minLow;  // Anti-chase: recent low

    @Column(precision = 5, scale = 4)
    private BigDecimal recent3minHigh;  // Anti-chase: recent high

    // Quality Metrics
    @Column(precision = 5, scale = 2)
    private BigDecimal qualityScore;  // 0-100: composite quality

    @Column(length = 20)
    private String signalStrength;  // "md" (medium) or "hi" (high) based on momentum

    @Column(length = 30)
    private String gateStatus;  // Bitmask/description: which gates passed

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean allGatesPassed;  // True if all 5 gates green

    // Execution Status
    @Column(length = 20)
    private String executionStatus;  // PENDING, FILLED, T1_HIT, SL_HIT, EXPIRED, CLOSED

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean isActive;  // True while waiting for execution

    @Column(columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    @Column
    private Instant filledAt;  // When order executed

    @Column
    private Instant closedAt;  // When T1/SL hit

    // Outcome (after execution)
    @Column(precision = 10, scale = 2)
    private BigDecimal actualEntryPremium;  // What we actually paid

    @Column(precision = 10, scale = 2)
    private BigDecimal actualExitPremium;  // What we actually received

    @Column(precision = 10, scale = 2)
    private BigDecimal pnlPerContract;  // Exit - Entry per contract

    @Column(precision = 10, scale = 2)
    private BigDecimal totalPnL;  // P&L × lot size

    @Column(length = 20)
    private String outcomeType;  // "WIN" (T1), "LOSS" (SL), "EXPIRED", "PARTIAL"

    // Metadata
    @Column(length = 200)
    private String notes;  // Trade notes, reasoning
}
