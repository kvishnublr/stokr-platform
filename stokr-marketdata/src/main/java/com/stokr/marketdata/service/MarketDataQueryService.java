package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketDataQueryService {

    private final MarketdataCandleRepository candleRepository;

    @Transactional(readOnly = true)
    public List<MarketdataCandle> lastBarsAsc(String symbol, String timeframe, int maxBars) {
        List<MarketdataCandle> desc = candleRepository.findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbol, timeframe);
        List<MarketdataCandle> asc = new ArrayList<>(desc);
        asc.sort(Comparator.comparing(MarketdataCandle::getOpenTime));
        if (asc.size() <= maxBars) {
            return asc;
        }
        return asc.subList(asc.size() - maxBars, asc.size());
    }

    /**
     * Bars with {@code open_time <= endOpenTimeInclusive}, oldest-first, capped at {@code maxBars}.
     * Used for deterministic replay/backtests so later candles do not leak into earlier evaluations.
     */
    @Transactional(readOnly = true)
    public List<MarketdataCandle> lastBarsAscEndingAt(String symbol, String timeframe, int maxBars, Instant endOpenTimeInclusive) {
        List<MarketdataCandle> desc = candleRepository.findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbol, timeframe);
        List<MarketdataCandle> filtered = new ArrayList<>();
        for (MarketdataCandle c : desc) {
            if (!c.getOpenTime().isAfter(endOpenTimeInclusive)) {
                filtered.add(c);
            }
        }
        filtered.sort(Comparator.comparing(MarketdataCandle::getOpenTime));
        if (filtered.size() <= maxBars) {
            return filtered;
        }
        return filtered.subList(filtered.size() - maxBars, filtered.size());
    }

    @Transactional(readOnly = true)
    public List<MarketdataCandle> rangeAsc(String symbol, String timeframe, Instant start, Instant end) {
        return candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbol, timeframe, start, end);
    }

    @Transactional(readOnly = true)
    public Page<MarketdataCandle> rangeAscPage(String symbol, String timeframe, Instant start, Instant end, Pageable pageable) {
        return candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                symbol, timeframe, start, end, pageable);
    }

    @Transactional(readOnly = true)
    public long rangeCount(String symbol, String timeframe, Instant start, Instant end) {
        return candleRepository.countBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalse(symbol, timeframe, start, end);
    }

    public List<MarketdataCandle> replay(List<MarketdataCandle> candles) {
        ArrayList<MarketdataCandle> copy = new ArrayList<>(candles);
        copy.sort(Comparator.comparing(MarketdataCandle::getOpenTime));
        return Collections.unmodifiableList(copy);
    }
}
