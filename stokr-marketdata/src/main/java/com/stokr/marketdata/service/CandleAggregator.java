package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CandleAggregator {

    private final ConcurrentHashMap<Key, PartialCandle> partial1m = new ConcurrentHashMap<>();
    private final Set<Key> dirtyKeys = ConcurrentHashMap.newKeySet();

    public MarketdataCandle applyTick(MarketdataTick tick, ZoneId zone) {
        Instant minuteOpen = floorToMinute(tick.getTickTime(), zone);
        Key key = new Key(tick.getSymbol(), "1m", minuteOpen);
        PartialCandle pc = partial1m.computeIfAbsent(key, k -> new PartialCandle(minuteOpen));
        pc.onTrade(tick.getPrice(), tick.getQuantity());
        dirtyKeys.add(key);
        return pc.toCandle(tick.getSymbol(), "1m");
    }

    /**
     * Atomically snapshot all dirty candles and clear the dirty set.
     * Called by the flush scheduler — returns the latest in-memory state for each
     * symbol/minute bucket that received at least one tick since the last flush.
     */
    public List<MarketdataCandle> snapshotDirtyAndClear() {
        Set<Key> snapshot = new HashSet<>(dirtyKeys);
        dirtyKeys.removeAll(snapshot);
        List<MarketdataCandle> result = new ArrayList<>(snapshot.size());
        for (Key k : snapshot) {
            PartialCandle pc = partial1m.get(k);
            if (pc != null) result.add(pc.toCandle(k.symbol(), k.tf()));
        }
        return result;
    }

    public MarketdataCandle rollup(MarketdataCandle bar1m, String targetTf, ZoneId zone) {
        if (!"5m".equals(targetTf)) {
            throw new IllegalArgumentException("Unsupported rollout timeframe: " + targetTf);
        }
        Instant bucketOpen = floorTo(bar1m.getOpenTime(), Duration.ofMinutes(5), zone);
        return MarketdataCandleFactory.fromBucket(bar1m, bucketOpen, "5m");
    }

    public void evictOlderThan(Instant cutoff) {
        partial1m.entrySet().removeIf(e -> e.getKey().openTime().isBefore(cutoff));
    }

    private static Instant floorToMinute(Instant ts, ZoneId zone) {
        ZonedDateTime z = ts.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
        return z.toInstant();
    }

    private static Instant floorTo(Instant ts, Duration bucket, ZoneId zone) {
        ZonedDateTime z = ts.atZone(zone);
        long minutes = bucket.toMinutes();
        long minuteOfDay = z.getHour() * 60L + z.getMinute();
        long floored = (minuteOfDay / minutes) * minutes;
        ZonedDateTime start = z.withHour(0).withMinute(0).withSecond(0).withNano(0).plusMinutes(floored);
        return start.toInstant();
    }

    private record Key(String symbol, String tf, Instant openTime) {
    }

    private static final class PartialCandle {
        private final Instant openTime;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume = BigDecimal.ZERO;
        private boolean initialized;

        private PartialCandle(Instant openTime) {
            this.openTime = openTime;
        }

        void onTrade(BigDecimal price, BigDecimal qty) {
            BigDecimal px = price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
            if (px == null) {
                return;
            }
            if (!initialized) {
                open = high = low = close = px;
                initialized = true;
            } else {
                high = high.max(px);
                low = low.min(px);
                close = px;
            }
            if (qty != null) {
                volume = volume.add(qty);
            }
        }

        MarketdataCandle toCandle(String symbol, String tf) {
            MarketdataCandle c = new MarketdataCandle();
            c.setSymbol(symbol);
            c.setTimeframe(tf);
            c.setOpenTime(openTime);
            c.setOpenPrice(open);
            c.setHighPrice(high);
            c.setLowPrice(low);
            c.setClosePrice(close);
            c.setVolume(volume.setScale(8, RoundingMode.HALF_UP));
            return c;
        }
    }

    private static final class MarketdataCandleFactory {
        private static MarketdataCandle fromBucket(MarketdataCandle bar1m, Instant bucketOpen, String tf) {
            MarketdataCandle c = new MarketdataCandle();
            c.setSymbol(bar1m.getSymbol());
            c.setTimeframe(tf);
            c.setOpenTime(bucketOpen);
            c.setOpenPrice(bar1m.getOpenPrice());
            c.setHighPrice(bar1m.getHighPrice());
            c.setLowPrice(bar1m.getLowPrice());
            c.setClosePrice(bar1m.getClosePrice());
            c.setVolume(bar1m.getVolume());
            return c;
        }
    }
}
