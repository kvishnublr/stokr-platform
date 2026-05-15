package com.stokr.strategy.runtime;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SymbolReadinessService {

    private final MarketdataCandleRepository candleRepository;

    @Value("${stokr.strategy.readiness.stale-seconds:600}")
    private long staleSeconds;

    /**
     * Validates 1m continuity/freshness for a symbol before scanner evaluation.
     */
    public Readiness assess(String symbol, Instant now) {
        List<MarketdataCandle> bars = candleRepository.findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbol, "1m");
        if (bars.isEmpty()) {
            return new Readiness(false, "NO_DATA");
        }
        MarketdataCandle latest = bars.get(0);
        if (latest.getOpenTime() == null) {
            return new Readiness(false, "NO_DATA");
        }
        long lag = Duration.between(latest.getOpenTime(), now).getSeconds();
        if (lag > staleSeconds) {
            return new Readiness(false, "STALE");
        }

        Instant prev = latest.getOpenTime();
        int checks = Math.min(120, bars.size() - 1);
        for (int i = 1; i <= checks; i++) {
            Instant t = bars.get(i).getOpenTime();
            if (t == null) {
                continue;
            }
            long delta = Duration.between(t, prev).getSeconds();
            if (delta > 120) {
                return new Readiness(false, "GAPS_PRESENT");
            }
            prev = t;
        }
        return new Readiness(true, "OK");
    }

    public record Readiness(boolean ready, String reason) {
    }
}
