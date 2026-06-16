package com.stokr.strategy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single instrument belonging to a universe group.
 * Supports equities, commodity futures, index/stock futures, options.
 */
@Getter
@Setter
@Entity
@Table(name = "strategy_universe_symbols")
public class StrategyUniverseSymbol {

    @Id
    @GeneratedValue
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private StrategyUniverseGroup group;

    /** Canonical symbol key, e.g. RELIANCE, GOLD, BANKNIFTY */
    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    /** Broker-specific trading symbol, e.g. BANKNIFTY26MAYFUT, GOLDM26JUNFUT */
    @Column(name = "trading_symbol", length = 128)
    private String tradingSymbol;

    /** Underlying instrument for derivatives, e.g. BANKNIFTY for its futures/options */
    @Column(name = "underlying_symbol", length = 64)
    private String underlyingSymbol;

    /** NSE | NFO | MCX | CDS | BSE */
    @Column(name = "exchange", nullable = false, length = 16)
    private String exchange = "NSE";

    /** Broker instrument token for real-time feed subscription */
    @Column(name = "instrument_token")
    private Long instrumentToken;

    /** EQ | FUT | OPT | COM | CUR */
    @Column(name = "instrument_type", nullable = false, length = 32)
    private String instrumentType = "EQ";

    /** Contract lot size (futures/options/commodities) */
    @Column(name = "lot_size")
    private Integer lotSize;

    /** Minimum price movement */
    @Column(name = "tick_size", precision = 12, scale = 4)
    private BigDecimal tickSize;

    /** Contract expiry date ??? null for equities, populated for derivatives */
    @Column(name = "expiry")
    private LocalDate expiry;

    /** Strike price for options */
    @Column(name = "strike", precision = 12, scale = 2)
    private BigDecimal strike;

    /** CE or PE for options, null for other instrument types */
    @Column(name = "option_type", length = 4)
    private String optionType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
