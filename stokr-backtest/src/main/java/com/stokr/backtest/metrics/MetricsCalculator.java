package com.stokr.backtest.metrics;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

@Component
public class MetricsCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public BigDecimal roi(BigDecimal pnl, BigDecimal startingCapital) {
        if (startingCapital.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return pnl.divide(startingCapital, MC);
    }

    public BigDecimal sharpe(List<BigDecimal> returns, BigDecimal riskFreeDaily) {
        if (returns.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), MC);
        BigDecimal var = BigDecimal.ZERO;
        for (BigDecimal r : returns) {
            BigDecimal d = r.subtract(mean);
            var = var.add(d.multiply(d));
        }
        var = var.divide(BigDecimal.valueOf(returns.size()), MC);
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(var.doubleValue()));
        if (std.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return mean.subtract(riskFreeDaily).divide(std, MC);
    }

    public BigDecimal maxDrawdown(List<BigDecimal> equityCurve) {
        BigDecimal peak = equityCurve.isEmpty() ? BigDecimal.ZERO : equityCurve.get(0);
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal eq : equityCurve) {
            if (eq.compareTo(peak) > 0) {
                peak = eq;
            }
            BigDecimal dd = peak.subtract(eq).divide(peak.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : peak, MC);
            if (dd.compareTo(maxDd) > 0) {
                maxDd = dd;
            }
        }
        return maxDd;
    }
}
