package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_performance", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"instance_id", "date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 18, scale = 2)
    private BigDecimal equity;

    @Column(precision = 18, scale = 2)
    private BigDecimal capital;

    @Column(precision = 10, scale = 4)
    private BigDecimal returns;

    @Column(precision = 10, scale = 4)
    private BigDecimal benchmarkReturns;

    @Column(precision = 10, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(name = "trades_count")
    @Builder.Default
    private Integer tradesCount = 0;

    @Column
    @Builder.Default
    private Integer wins = 0;

    @Column
    @Builder.Default
    private Integer losses = 0;

    @Column(name = "avg_win", precision = 18, scale = 2)
    private BigDecimal avgWin;

    @Column(name = "avg_loss", precision = 18, scale = 2)
    private BigDecimal avgLoss;

    @Column(name = "win_rate", precision = 5, scale = 4)
    private BigDecimal winRate;

    @Column(name = "profit_factor", precision = 8, scale = 4)
    private BigDecimal profitFactor;

    @Column(name = "sharpe_ratio", precision = 8, scale = 4)
    private BigDecimal sharpeRatio;

    @Column(precision = 18, scale = 2)
    private BigDecimal pnl;

    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String metadata = "{}";

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    public BigDecimal calculateWinRate() {
        int total = wins + losses;
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal calculateProfitFactor() {
        BigDecimal totalWin = avgWin != null ? avgWin.multiply(BigDecimal.valueOf(wins)) : BigDecimal.ZERO;
        BigDecimal totalLoss = avgLoss != null ? avgLoss.multiply(BigDecimal.valueOf(losses)).abs() : BigDecimal.ZERO;
        if (totalLoss.compareTo(BigDecimal.ZERO) == 0) {
            return totalWin.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999) : BigDecimal.ONE;
        }
        return totalWin.divide(totalLoss, 4, java.math.RoundingMode.HALF_UP);
    }

    public void updateMetrics() {
        this.winRate = calculateWinRate();
        this.profitFactor = calculateProfitFactor();
    }
}
