package com.stokr.marketdata.tick;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "tick_candle_data", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"symbol", "timeframe", "timestamp"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TickCandleData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal close;

    @Column(nullable = false)
    private long volume;

    @Column(name = "trade_count", nullable = false)
    private int tradeCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
