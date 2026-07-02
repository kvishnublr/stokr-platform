package com.stokr.marketdata.tick;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "tick_anomalies", indexes = {
    @Index(name = "idx_anomaly_type_ts", columnList = "symbol,anomaly_type,detected_ts DESC"),
    @Index(name = "idx_anomaly_unresolved", columnList = "resolved,detected_ts", unique = false)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TickAnomaly {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "anomaly_type", nullable = false, length = 30)
    private String anomalyType;

    @Column(name = "detected_ts", nullable = false)
    private LocalDateTime detectedTs;

    @Column(name = "price_at_event", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAtEvent;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal magnitude;

    @Column(name = "volume_at_event", nullable = false)
    private long volumeAtEvent;

    @Column(name = "vwap_deviation", precision = 12, scale = 4)
    private BigDecimal vwapDeviation;

    @Column(length = 10)
    private String direction;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "signal_id")
    private Long signalId;

    @Column(columnDefinition = "text default ''")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
