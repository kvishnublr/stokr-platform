package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(name = "signal_type", nullable = false, length = 10)
    private String signalType; // ENTRY, EXIT

    @Column(nullable = false, length = 10)
    private String side; // BUY, SELL

    @Column(precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(name = "entry_price", precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "target_price", precision = 18, scale = 4)
    private BigDecimal targetPrice;

    @Column(name = "stop_loss", precision = 18, scale = 4)
    private BigDecimal stopLoss;

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, EXECUTED, SKIPPED, FAILED

    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String metadata = "{}";

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "skipped_at")
    private Instant skippedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    public boolean isEntry() {
        return "ENTRY".equalsIgnoreCase(signalType);
    }

    public boolean isExit() {
        return "EXIT".equalsIgnoreCase(signalType);
    }

    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(side);
    }

    public boolean isSell() {
        return "SELL".equalsIgnoreCase(side);
    }

    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(status);
    }

    public boolean isExecuted() {
        return "EXECUTED".equalsIgnoreCase(status);
    }
}
