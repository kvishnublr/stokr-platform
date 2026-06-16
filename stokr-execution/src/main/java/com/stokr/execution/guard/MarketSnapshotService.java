package com.stokr.execution.guard;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketSnapshotService {

    private final MarketdataTickRepository tickRepository;
    private final MarketdataCandleRepository candleRepository;
    private final JdbcTemplate jdbcTemplate;

    public MarketSnapshot capture(String symbol, Instant now) {
        MarketdataTick last = tickRepository.findFirstBySymbolOrderByTickTimeDesc(symbol).orElse(null);
        Instant lastTickAt = last != null ? last.getTickTime() : null;
        BigDecimal ltp = last != null ? nz(last.getPrice()) : BigDecimal.ZERO;

        BigDecimal volatilityPct = computeVolatilityPct(symbol, now);
        BigDecimal estimatedSlippagePct = estimateSlippagePct(volatilityPct);

        Map<String, Object> feed = latestPlatformFeedRow();
        Instant hb = asInstant(feed.get("last_heartbeat_at"));
        String ws = String.valueOf(feed.getOrDefault("websocket_state", "UNKNOWN"));
        Integer reconnect = asInt(feed.get("reconnect_count"));
        Integer subs = asInt(feed.get("subscription_count"));
        boolean open = isMarketOpen(now);

        return new MarketSnapshot(
                now,
                ltp,
                null,
                null,
                null,
                null,
                lastTickAt,
                hb,
                ws,
                reconnect,
                subs,
                volatilityPct,
                estimatedSlippagePct,
                open
        );
    }

    private Map<String, Object> latestPlatformFeedRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select websocket_state,last_heartbeat_at,reconnect_count,subscription_count
                from platform_broker_feed_sessions
                where deleted=false and lower(vendor_code)='zerodha'
                order by updated_at desc
                limit 1
                """);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private BigDecimal computeVolatilityPct(String symbol, Instant now) {
        List<MarketdataCandle> bars = candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                symbol,
                "1m",
                now.minusSeconds(60L * 10),
                now
        );
        if (bars.size() < 2) return BigDecimal.ZERO;
        MarketdataCandle first = bars.getFirst();
        MarketdataCandle last = bars.getLast();
        BigDecimal o = nz(first.getOpenPrice());
        BigDecimal c = nz(last.getClosePrice());
        if (o.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return c.subtract(o).abs().multiply(BigDecimal.valueOf(100)).divide(o, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateSlippagePct(BigDecimal volatilityPct) {
        if (volatilityPct == null) return BigDecimal.ZERO;
        return volatilityPct.multiply(new BigDecimal("0.12")).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Instant asInstant(Object v) {
        if (v instanceof Timestamp ts) return ts.toInstant();
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private static boolean isMarketOpen(Instant now) {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        var z = now.atZone(zone);
        DayOfWeek d = z.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = z.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }
}

