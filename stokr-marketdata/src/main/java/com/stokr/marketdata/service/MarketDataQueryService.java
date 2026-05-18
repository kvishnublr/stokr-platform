package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MarketDataQueryService {

    private final MarketdataCandleRepository candleRepository;

    @Transactional(readOnly = true)
    public List<MarketdataCandle> lastBarsAsc(String symbol, String timeframe, int maxBars) {
        List<MarketdataCandle> asc = fetchAscWithFallback(symbol, timeframe, null, Math.max(500, maxBars * 6));
        asc.sort(Comparator.comparing(MarketdataCandle::getOpenTime));
        if (asc.size() <= maxBars) {
            return asc;
        }
        return asc.subList(asc.size() - maxBars, asc.size());
    }

    /**
     * Bars with {@code open_time <= endOpenTimeInclusive}, oldest-first, capped at {@code maxBars}.
     * Uses an index-friendly bounded query at the replay instant — not "latest 500 rows in DB then filter",
     * which breaks historical replay when the DB tail is newer than {@code endOpenTimeInclusive}.
     */
    @Transactional(readOnly = true)
    public List<MarketdataCandle> lastBarsAscEndingAt(String symbol, String timeframe, int maxBars, Instant endOpenTimeInclusive) {
        List<MarketdataCandle> asc = fetchAscWithFallback(symbol, timeframe, endOpenTimeInclusive, Math.max(maxBars * 8, 1200));
        if (asc.size() <= maxBars) {
            return asc;
        }
        return asc.subList(asc.size() - maxBars, asc.size());
    }

    @Transactional(readOnly = true)
    public List<MarketdataCandle> rangeAsc(String symbol, String timeframe, Instant start, Instant end) {
        String tf = normalizeTf(timeframe);
        if ("5m".equals(tf) || "15m".equals(tf)) {
            List<MarketdataCandle> raw = candleRepository.findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbolCandidates(symbol), tf, start, end);
            if (!raw.isEmpty()) {
                return raw;
            }
            int intervalMinutes = "15m".equals(tf) ? 15 : 5;
            List<MarketdataCandle> m1 = candleRepository.findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                    symbolCandidates(symbol), "1m", start.minusSeconds(intervalMinutes * 60L), end);
            return aggregateToInterval(m1, symbol, tf, intervalMinutes, start, end);
        }
        return candleRepository.findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbolCandidates(symbol), tf, start, end);
    }

    @Transactional(readOnly = true)
    public Page<MarketdataCandle> rangeAscPage(String symbol, String timeframe, Instant start, Instant end, Pageable pageable) {
        List<MarketdataCandle> rows = candleRepository.findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                symbolCandidates(symbol), timeframe, start, end);
        int offset = (int) pageable.getOffset();
        if (offset >= rows.size()) {
            return Page.empty(pageable);
        }
        int to = Math.min(rows.size(), offset + pageable.getPageSize());
        List<MarketdataCandle> pageRows = rows.subList(offset, to);
        return new org.springframework.data.domain.PageImpl<>(pageRows, pageable, rows.size());
    }

    @Transactional(readOnly = true)
    public long rangeCount(String symbol, String timeframe, Instant start, Instant end) {
        return rangeAsc(symbol, timeframe, start, end).size();
    }

    public List<MarketdataCandle> replay(List<MarketdataCandle> candles) {
        ArrayList<MarketdataCandle> copy = new ArrayList<>(candles);
        copy.sort(Comparator.comparing(MarketdataCandle::getOpenTime));
        return Collections.unmodifiableList(copy);
    }

    private List<String> symbolCandidates(String symbol) {
        if (symbol == null) {
            return List.of();
        }
        String raw = symbol.trim();
        if (raw.isBlank()) {
            return List.of();
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        out.add(raw);
        out.add(upper);
        switch (upper) {
            case "NIFTY_FUT", "NIFTY" -> {
                out.add("NIFTY 50");
                out.add("NIFTY_50");
            }
            case "BANKNIFTY_FUT", "BANKNIFTY" -> {
                out.add("NIFTY BANK");
                out.add("NIFTY_BANK");
                out.add("BANK NIFTY");
            }
            case "FINNIFTY_FUT", "FINNIFTY" -> {
                out.add("NIFTY FIN SERVICE");
                out.add("NIFTY_FIN_SERVICE");
            }
            default -> {
                if (upper.endsWith("_FUT")) {
                    out.add(upper.substring(0, upper.length() - 4));
                }
                if (upper.contains(" ")) {
                    out.add(upper.replace(' ', '_'));
                }
                if (upper.contains("_")) {
                    out.add(upper.replace('_', ' '));
                }
            }
        }
        return List.copyOf(out);
    }

    private String normalizeTf(String timeframe) {
        return timeframe == null ? "1m" : timeframe.trim().toLowerCase(Locale.ROOT);
    }

    private List<MarketdataCandle> fetchAscWithFallback(String symbol, String timeframe, Instant endInclusive, int maxBars) {
        String tf = normalizeTf(timeframe);
        List<String> symbols = symbolCandidates(symbol);
        if (!"5m".equals(tf) && !"15m".equals(tf)) {
            if (endInclusive == null) {
                List<MarketdataCandle> desc = candleRepository.findTop500BySymbolInAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbols, tf);
                ArrayList<MarketdataCandle> asc = new ArrayList<>(desc);
                Collections.reverse(asc);
                return asc;
            }
            Page<MarketdataCandle> page = candleRepository.findBySymbolInAndTimeframeAndOpenTimeLessThanEqualAndDeletedFalse(
                    symbols, tf, endInclusive, PageRequest.of(0, maxBars, Sort.by(Sort.Direction.DESC, "openTime")));
            ArrayList<MarketdataCandle> asc = new ArrayList<>(page.getContent());
            Collections.reverse(asc);
            return asc;
        }

        int intervalMinutes = "15m".equals(tf) ? 15 : 5;
        List<MarketdataCandle> rawTf;
        if (endInclusive == null) {
            List<MarketdataCandle> desc = candleRepository.findTop500BySymbolInAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbols, tf);
            rawTf = new ArrayList<>(desc);
            Collections.reverse(rawTf);
        } else {
            Page<MarketdataCandle> p = candleRepository.findBySymbolInAndTimeframeAndOpenTimeLessThanEqualAndDeletedFalse(
                    symbols, tf, endInclusive, PageRequest.of(0, maxBars, Sort.by(Sort.Direction.DESC, "openTime")));
            rawTf = new ArrayList<>(p.getContent());
            Collections.reverse(rawTf);
        }
        if (!rawTf.isEmpty()) {
            return rawTf;
        }

        List<MarketdataCandle> m1;
        if (endInclusive == null) {
            List<MarketdataCandle> desc = candleRepository.findTop500BySymbolInAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbols, "1m");
            m1 = new ArrayList<>(desc);
            Collections.reverse(m1);
        } else {
            Page<MarketdataCandle> p = candleRepository.findBySymbolInAndTimeframeAndOpenTimeLessThanEqualAndDeletedFalse(
                    symbols, "1m", endInclusive, PageRequest.of(0, maxBars * Math.max(6, intervalMinutes + 1), Sort.by(Sort.Direction.DESC, "openTime")));
            m1 = new ArrayList<>(p.getContent());
            Collections.reverse(m1);
        }
        return aggregateToInterval(m1, symbol, tf, intervalMinutes, null, endInclusive);
    }

    private List<MarketdataCandle> aggregateToInterval(
            List<MarketdataCandle> oneMinuteBars,
            String symbol,
            String timeframe,
            int intervalMinutes,
            Instant startInclusive,
            Instant endInclusive
    ) {
        if (oneMinuteBars.isEmpty()) {
            return List.of();
        }
        Map<Long, Bucket> buckets = new HashMap<>();
        long intervalSeconds = intervalMinutes * 60L;
        for (MarketdataCandle c : oneMinuteBars) {
            Instant t = c.getOpenTime();
            if (startInclusive != null && t.isBefore(startInclusive)) {
                continue;
            }
            if (endInclusive != null && t.isAfter(endInclusive)) {
                continue;
            }
            long bucket = (t.getEpochSecond() / intervalSeconds) * intervalSeconds;
            Bucket b = buckets.computeIfAbsent(bucket, ignored -> new Bucket());
            b.add(c);
        }
        ArrayList<MarketdataCandle> out = new ArrayList<>(buckets.size());
        buckets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Bucket b = e.getValue();
            if (b.open == null || b.high == null || b.low == null || b.close == null) {
                return;
            }
            MarketdataCandle c = new MarketdataCandle();
            c.setSymbol(symbol);
            c.setTimeframe(timeframe);
            c.setOpenTime(Instant.ofEpochSecond(e.getKey()));
            c.setOpenPrice(b.open);
            c.setHighPrice(b.high);
            c.setLowPrice(b.low);
            c.setClosePrice(b.close);
            c.setVolume(b.volume);
            out.add(c);
        });
        return out;
    }

    private static final class Bucket {
        private java.math.BigDecimal open;
        private java.math.BigDecimal high;
        private java.math.BigDecimal low;
        private java.math.BigDecimal close;
        private java.math.BigDecimal volume = java.math.BigDecimal.ZERO;

        private void add(MarketdataCandle c) {
            if (open == null) {
                open = c.getOpenPrice();
            }
            close = c.getClosePrice();
            if (high == null || c.getHighPrice().compareTo(high) > 0) {
                high = c.getHighPrice();
            }
            if (low == null || c.getLowPrice().compareTo(low) < 0) {
                low = c.getLowPrice();
            }
            if (c.getVolume() != null) {
                volume = volume.add(c.getVolume());
            }
        }
    }
}
