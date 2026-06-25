package com.stokr.engine;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_signals")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 10)
    private Side side;

    @Column(name = "entry_price", precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "stop_loss", precision = 15, scale = 4)
    private BigDecimal stopLoss;

    @Column(name = "target", precision = 15, scale = 4)
    private BigDecimal target;

    @Column(name = "confidence", precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "GENERATED"; // GENERATED, EXECUTED, REJECTED, EXPIRED

    @Column(name = "movement_score")
    private Double movementScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_source", length = 20)
    @Builder.Default
    private SignalSource source = SignalSource.INTERNAL;

    @Column(name = "scanner_name", length = 100)
    private String scannerName;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "failed_filters", length = 200)
    private String failedFilters;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "entry_time")
    private Instant entryTime;

    @Column(name = "exit_time")
    private Instant exitTime;

    @Column(name = "exit_type", length = 20)
    private String exitType; // SL_HIT, TARGET_HIT, EOD_EXIT, REJECTED, PENDING

    @Column(name = "trail_trigger_pct")
    @Builder.Default
    private Double trailTriggerPct = 0.5;

    @Column(name = "trail_distance_pct")
    @Builder.Default
    private Double trailDistancePct = 0.3;

    public enum Side { BUY, SELL }

    public enum SignalSource { INTERNAL, CHARTINK }
}
