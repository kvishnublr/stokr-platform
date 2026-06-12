package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "automated_a_plus_trades", indexes = {
        @Index(name = "idx_automated_trades_symbol_status", columnList = "symbol, status"),
        @Index(name = "idx_automated_trades_entry_time", columnList = "entry_time DESC"),
        @Index(name = "idx_automated_trades_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomatedAPlusTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal entryPrice;

    @Column(nullable = false)
    private Instant entryTime;

    @Column(nullable = false)
    private Integer entryAiScore;

    private Integer currentAiScore;

    @Column(nullable = false, length = 10)
    private String side; // BUY or SELL

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, length = 50)
    private String status; // ACTIVE, EXIT_PENDING, EXITED, CANCELLED

    @Column(length = 100)
    private String exitTrigger;

    private BigDecimal exitPrice;
    private Instant exitTime;

    @Column(precision = 18, scale = 4)
    private BigDecimal pnl;

    @Column(precision = 10, scale = 4)
    private BigDecimal pnlPct;

    // Exit criteria tracking
    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean aiScoreDropReason = false;

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean oppositeSignalTriggered = false;

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean hardSlHit = false;

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean hardTpHit = false;

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean marketCloseExit = false;

    // OMS integration
    @Column(length = 100)
    private String entryOrderId;

    @Column(length = 100)
    private String exitOrderId;

    @Column(length = 50)
    private String entryExecutionStatus;

    @Column(length = 50)
    private String exitExecutionStatus;

    @Column(columnDefinition = "uuid")
    private UUID traderId;

    @Builder.Default
    @Column(length = 100)
    private String strategyName = "AI_PLUS_SETUP_AUTO";

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Status {
        ACTIVE, EXIT_PENDING, EXITED, CANCELLED
    }

    public enum Side {
        BUY, SELL
    }

    public enum ExitReason {
        AI_SCORE_DROP, OPPOSITE_SIGNAL, HARD_SL, HARD_TP, MARKET_CLOSE, MANUAL
    }
}
