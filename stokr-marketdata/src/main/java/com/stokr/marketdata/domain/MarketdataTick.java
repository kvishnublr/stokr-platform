package com.stokr.marketdata.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "marketdata_ticks")
public class MarketdataTick extends BaseEntity {

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "tick_time", nullable = false)
    private Instant tickTime;

    @Column(name = "price", nullable = false, precision = 24, scale = 8)
    private BigDecimal price;

    @Column(name = "quantity", precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "trade_side", length = 8)
    private String tradeSide;

    @Column(name = "source", length = 64)
    private String source;
}
