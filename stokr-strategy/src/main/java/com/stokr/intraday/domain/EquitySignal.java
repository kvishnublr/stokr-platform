package com.stokr.intraday.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "equity_signals", indexes = {
        @Index(name = "idx_symbol_active", columnList = "symbol_name, is_active, time_detected"),
        @Index(name = "idx_confidence_time", columnList = "confidence_level, time_detected"),
        @Index(name = "idx_sector_direction", columnList = "sector, direction, is_active"),
        @Index(name = "idx_created", columnList = "created_at"),
        @Index(name = "idx_outcome", columnList = "outcome_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquitySignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long signalId;

    // Stock & Market Info
    @Column(nullable = false)
    private String symbolName;          // "RELIANCE", "TCS", "BIOCON", etc.

    @Column(nullable = false)
    private String sector;              // "IT", "BANKING", "PHARMA", etc.

    @Column(nullable = false)
    private String tier;                // "TIER1", "TIER2", "TIER3", "INDEX", "ETF"

    @Column(nullable = false)
    private String direction;           // "LONG" or "SHORT"

    @Column(nullable = false)
    private BigDecimal currentPrice;    // Stock price at signal time

    // Entry Levels
    @Column(nullable = false)
    private BigDecimal entryLevel;

    @Column(name = "stop_loss_level", nullable = false)
    private BigDecimal stopLossLevel;

    @Column(name = "target_level1", nullable = false)
    private BigDecimal targetLevel1;    // 0.7% target

    @Column(name = "target_level2")
    private BigDecimal targetLevel2;    // 1.4% target

    // ADV CASH Signal Analysis (10-step)
    private BigDecimal obiScore;        // -1.0 to +1.0
    private BigDecimal obiSlope;        // Trend of OBI
    private BigDecimal volumeLevel;     // Volume compared to average
    private BigDecimal bidAskSpread;    // Spread % (should be < 0.15%)
    private BigDecimal vixLevel;        // Market volatility
    private Integer timeframeAlignment; // 1, 2, or 3 (how many TF agree)

    // Quality Metrics
    @Column(nullable = false)
    private Integer confidenceLevel;    // 1 (low), 2 (medium), 3 (high)

    @Column(nullable = false)
    private BigDecimal qualityScore;    // 0-100

    // Execution Status
    @Column(nullable = false)
    private String executionStatus;     // "PENDING", "FILLED", "CLOSED", "CANCELLED"

    // Entry Execution
    private BigDecimal actualEntryPrice;
    private Instant timeFilledAt;
    private Integer quantity;           // Number of shares

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
