package com.stokr.strategy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "universe_symbols")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UniverseSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "trading_symbol")
    private String tradingSymbol;

    @Column(name = "underlying_symbol")
    private String underlyingSymbol;

    @Column(nullable = false)
    @Builder.Default
    private String exchange = "NSE";

    @Column(name = "instrument_token")
    private String instrumentToken;

    @Column(name = "instrument_type")
    @Builder.Default
    private String instrumentType = "EQ";

    @Column(name = "lot_size")
    @Builder.Default
    private Integer lotSize = 1;

    @Column(name = "tick_size")
    @Builder.Default
    private BigDecimal tickSize = new BigDecimal("0.05");

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
