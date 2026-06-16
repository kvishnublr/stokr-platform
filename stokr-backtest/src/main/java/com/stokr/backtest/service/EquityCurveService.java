package com.stokr.backtest.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class EquityCurveService {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public List<EquityPoint> buildFromTradePnls(List<BigDecimal> tradePnls) {
        List<EquityPoint> points = new ArrayList<>(tradePnls.size());
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        for (int i = 0; i < tradePnls.size(); i++) {
            cumulative = cumulative.add(tradePnls.get(i));
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : peak.subtract(cumulative).divide(peak, MC);
            points.add(new EquityPoint(i + 1, cumulative, drawdown));
        }
        return points;
    }

    public record EquityPoint(int index, BigDecimal cumulativePnl, BigDecimal drawdown) {
    }
}
