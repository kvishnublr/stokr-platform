package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains ingest-side candle integrity: evicts stale partial builder state and detects obvious OHLC gaps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleFinalizationService {

    private final CandleAggregator candleAggregator;
    private final MarketdataCandleRepository candleRepository;

    /**
     * Drops in-memory partial buckets older than cutoff (does not delete persisted finalized candles).
     */
    public void evictPartialStateOlderThan(Instant cutoffUtc) {
        candleAggregator.evictOlderThan(cutoffUtc);
    }

    /**
     * Finds sequences where consecutive 1m candles for a symbol have a gap larger than one period (replay diagnostics).
     */
    @Transactional(readOnly = true)
    public List<CandleGap> detectGaps(String symbol, String timeframe, Instant rangeStart, Instant rangeEnd) {
        List<MarketdataCandle> asc =
                candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, timeframe, rangeStart, rangeEnd);
        if (asc.size() < 2) {
            return List.of();
        }
        long periodMs = timeframePeriodMillis(timeframe);
        List<CandleGap> gaps = new ArrayList<>();
        for (int i = 1; i < asc.size(); i++) {
            long delta = asc.get(i).getOpenTime().toEpochMilli() - asc.get(i - 1).getOpenTime().toEpochMilli();
            if (delta > periodMs + 1) {
                gaps.add(new CandleGap(asc.get(i - 1).getOpenTime(), asc.get(i).getOpenTime(), delta - periodMs));
            }
        }
        return gaps;
    }

    private static long timeframePeriodMillis(String tf) {
        if (tf != null && tf.endsWith("m")) {
            return Long.parseLong(tf.substring(0, tf.length() - 1)) * 60_000L;
        }
        return 60_000L;
    }

    public record CandleGap(Instant afterOpen, Instant nextOpen, long excessMillis) {
    }
}
