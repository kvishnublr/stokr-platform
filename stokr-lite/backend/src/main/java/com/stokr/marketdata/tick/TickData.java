package com.stokr.marketdata.tick;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "tick_data", indexes = {
    @Index(name = "idx_tick_symbol_ts", columnList = "symbol,exchange_ts DESC"),
    @Index(name = "idx_tick_created_at", columnList = "created_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TickData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "exchange_ts", nullable = false)
    private LocalDateTime exchangeTs;

    @Column(name = "received_ts", nullable = false)
    private LocalDateTime receivedTs;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal ltp;

    @Column(nullable = false)
    private long volume;

    @Column(name = "minute_volume", nullable = false)
    private long minuteVolume;

    @Column(name = "buy_quantity")
    private long buyQuantity;

    @Column(name = "sell_quantity")
    private long sellQuantity;

    @Column(name = "change_pct", precision = 8, scale = 4)
    private BigDecimal changePct;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
