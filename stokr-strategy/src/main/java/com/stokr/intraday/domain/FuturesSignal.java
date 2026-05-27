package com.stokr.intraday.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "futures_signals", indexes = {
        @Index(name = "idx_strategy_active", columnList = "strategy_name, is_active, time_detected"),
        @Index(name = "idx_quality_time", columnList = "quality_score, time_detected"),
        @Index(name = "idx_symbol_direction", columnList = "symbol_name, direction, is_active"),
        @Index(name = "idx_created", columnList = "created_at"),
        @Index(name = "idx_outcome", columnList = "outcome_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FuturesSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long signalId;

    // Strategy & Market Info
    @Column(nullable = false)
    private String strategyName;        // "S3" or "S7"

    @Column(nullable = false)
    private String symbolName;          // "NIFTY" or "BANKNIFTY"

    @Column(nullable = false)
    private String direction;           // "LONG" or "SHORT"

    @Column(nullable = false)
    private BigDecimal currentPrice;    // Index price at signal time

    // Entry Levels
    @Column(nullable = false)
    private BigDecimal entryLevel;

    @Column(nullable = false)
    private BigDecimal stopLossLevel;

    @Column(nullable = false)
    private BigDecimal targetLevel1;

    private BigDecimal targetLevel2;

    // Gate Analysis (for S3: VWAP, trend, volume; for S7: range, momentum)
    private BigDecimal vwapLevel;
    private BigDecimal sma20;
    private BigDecimal sma50;
    private BigDecimal range5m;
    private BigDecimal momentum5m;
    private BigDecimal volume5m;

    // Quality Metrics
    @Column(nullable = false)
    private BigDecimal qualityScore;    // 0-100

    private String signalStrength;      // "hi", "medium"

    // Execution Status
    @Column(nullable = false)
    private String executionStatus;     // "PENDING", "FILLED", "CLOSED", "CANCELLED"

    // Entry Execution
    private BigDecimal actualEntryPrice;
    private Instant timeFilledAt;
    private Integer lotSize;

    // Exit Execution
    private BigDecimal actualExitPrice;
    private Instant timeClosedAt;
    private String outcomeType;         // "WIN", "LOSS", "EXPIRED", "CANCELLED"

    // P&L (in rupees)
    private BigDecimal profitLoss;
    private BigDecimal slippageEntry;   // Negative = adverse
    private BigDecimal slippageExit;    // Negative = adverse

    // Timestamps
    @Column(nullable = false)
    private Instant timeDetected;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // Flags
    private boolean isActive;

    // Comment/notes
    private String notes;
}
