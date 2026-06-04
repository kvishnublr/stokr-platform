package com.stokr.intraday.metrics.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_flow_snapshots", indexes = {
    @Index(name = "idx_order_flow_symbol_time", columnList = "symbol, timestamp DESC"),
    @Index(name = "idx_order_flow_pressure", columnList = "symbol, buyer_pressure_score DESC"),
    @Index(name = "idx_order_flow_liquidity", columnList = "symbol, liquidity_score DESC"),
    @Index(name = "idx_order_flow_timestamp", columnList = "timestamp DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderFlowSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private Instant timestamp;

    // Bid-Ask Data
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal bidPrice;

    @Column(nullable = false)
    private Long bidVolume;

    @Column(name = "bid_level_1")
    private Long bidLevel1;

    @Column(name = "bid_level_2")
    private Long bidLevel2;

    @Column(name = "bid_level_3")
    private Long bidLevel3;

    @Column(name = "bid_level_4")
    private Long bidLevel4;

    @Column(name = "bid_level_5")
    private Long bidLevel5;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal askPrice;

    @Column(nullable = false)
    private Long askVolume;

    @Column(name = "ask_level_1")
    private Long askLevel1;

    @Column(name = "ask_level_2")
    private Long askLevel2;

    @Column(name = "ask_level_3")
    private Long askLevel3;

    @Column(name = "ask_level_4")
    private Long askLevel4;

    @Column(name = "ask_level_5")
    private Long askLevel5;

    // Calculated Metrics
    @Column(precision = 10, scale = 4)
    private BigDecimal spread;

    @Column(precision = 10, scale = 6)
    private BigDecimal spreadPct;

    @Column(precision = 20, scale = 4)
    private BigDecimal midPrice;

    @Column(precision = 10, scale = 4)
    private BigDecimal bidAskRatio;

    @Column
    private Long imbalance;

    // Liquidity Metrics
    @Column
    private Long cumulativeBid10Levels;

    @Column
    private Long cumulativeAsk10Levels;

    @Column
    private Integer liquidityScore;  // 0-100

    // Pressure Indicators
    @Column
    private Integer buyerPressureScore;  // 0-100

    @Column
    private Integer sellerPressureScore;  // 0-100

    // Additional Analysis Fields
    @Column
    private Integer bidAskMomentum;  // Rate of change of ratio

    @Column
    private Long largeOrdersOnBid;  // Orders > 10k shares

    @Column
    private Long largeOrdersOnAsk;  // Orders > 10k shares

    @Column(length = 50)
    private String pressureType;  // BUY_PRESSURE, SELL_PRESSURE, NEUTRAL, BALANCED

    @Column(precision = 10, scale = 4)
    private BigDecimal buyerInitiatedPct;  // % of buy-side trades

    @Column(precision = 10, scale = 4)
    private BigDecimal sellerInitiatedPct;  // % of sell-side trades

    // Metadata
    @Column(length = 10)
    private String exchange;  // NSE

    @Column
    private Boolean isValid;  // Data quality check

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // Calculated getter
    public BigDecimal getMidPrice() {
        if (bidPrice == null || askPrice == null) {
            return null;
        }
        return bidPrice.add(askPrice)
            .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
    }

    // Helper method to check if snapshot is recent (< 1 second old)
    public boolean isRecent() {
        if (timestamp == null) return false;
        return Instant.now().minus(java.time.Duration.ofSeconds(1))
            .isBefore(timestamp);
    }

    // Helper method to check spread percentage
    public boolean hasNarrowSpread() {
        return spreadPct != null &&
               spreadPct.compareTo(BigDecimal.valueOf(0.02)) < 0;
    }

    // Helper method to check strong buy pressure
    public boolean hasStrongBuyPressure() {
        return buyerPressureScore != null && buyerPressureScore > 70;
    }

    // Helper method to check strong sell pressure
    public boolean hasStrongSellPressure() {
        return sellerPressureScore != null && sellerPressureScore > 70;
    }

    // Helper method to check adequate liquidity
    public boolean hasAdequateLiquidity() {
        return liquidityScore != null && liquidityScore > 60;
    }
}
