package com.stokr.backtest.execution;

import com.stokr.marketdata.domain.MarketdataCandle;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Market data integrity checks for replay streams (PR-4). Fail-fast on structural corruption; score gaps softly.
 */
@Slf4j
public final class CandleStreamQualityValidator {

    private CandleStreamQualityValidator() {
    }

    public record QualityReport(int score100, List<String> issues) {
        public boolean ok() {
            return issues.isEmpty();
        }
    }

    public static QualityReport validatePage(List<MarketdataCandle> ascendingPage, Instant expectedPrevOpen) {
        List<String> issues = new ArrayList<>();
        int score = 100;
        Instant prev = expectedPrevOpen;
        Instant lastOpen = null;
        for (MarketdataCandle c : ascendingPage) {
            if (c.getOpenTime() == null) {
                issues.add("null_open_time");
                score -= 50;
                continue;
            }
            if (lastOpen != null && !c.getOpenTime().isAfter(lastOpen)) {
                issues.add("out_of_order_or_duplicate:" + c.getOpenTime());
                score -= 40;
            }
            if (prev != null && c.getOpenTime().isBefore(prev)) {
                issues.add("gap_backward:" + c.getOpenTime());
                score -= 30;
            }
            if (!ohlcSane(c)) {
                issues.add("invalid_ohlc:" + c.getOpenTime());
                score -= 35;
            }
            lastOpen = c.getOpenTime();
            prev = c.getOpenTime();
        }
        score = Math.max(0, score);
        return new QualityReport(score, issues);
    }

    private static boolean ohlcSane(MarketdataCandle c) {
        if (c.getOpenPrice() == null || c.getHighPrice() == null || c.getLowPrice() == null || c.getClosePrice() == null) {
            return false;
        }
        if (c.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (c.getHighPrice().compareTo(c.getLowPrice()) < 0) {
            return false;
        }
        return c.getHighPrice().compareTo(c.getOpenPrice()) >= 0 && c.getHighPrice().compareTo(c.getClosePrice()) >= 0
                && c.getLowPrice().compareTo(c.getOpenPrice()) <= 0 && c.getLowPrice().compareTo(c.getClosePrice()) <= 0;
    }
}
