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
@Table(name = "marketdata_candles")
public class MarketdataCandle extends BaseEntity {

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(name = "open_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal closePrice;

    @Column(name = "volume", precision = 24, scale = 8)
    private BigDecimal volume;
}
