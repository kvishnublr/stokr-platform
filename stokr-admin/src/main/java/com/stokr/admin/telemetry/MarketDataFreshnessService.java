package com.stokr.admin.telemetry;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB-derived market candle freshness (central store). Does not yet observe vendor websocket packet rates.
 */
@Service
@RequiredArgsConstructor
public class MarketDataFreshnessService {

    /** If latest 1m candle is older than this, feeds are considered stale for ops. */
    private static final long STALE_LAG_SECONDS = 600L;

    private final EntityManager entityManager;

    public Map<String, Object> snapshot(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        Double lag1m = queryLagSeconds1m();
        m.put("latest1mLagSeconds", lag1m);
        if (lag1m == null) {
            m.put("status", "UNKNOWN");
        } else if (lag1m > STALE_LAG_SECONDS) {
            m.put("status", "STALE");
        } else {
            m.put("status", "OK");
        }
        m.put("staleThresholdSeconds", STALE_LAG_SECONDS);
        m.put("distinctSymbols", queryLong("select count(distinct symbol) from marketdata_candles where deleted = false"));
        m.put("worstSymbols1m", worstSymbolsSample());
        m.put("candles1mPerMinuteApprox", candles1mPerMinuteApprox());
        m.put("note", "Lag = DB clock minus max(open_time) for 1m candles. Throughput = new 1m rows in last 5m / 5.");
        m.put("collectedAt", now.toString());
        return m;
    }

    private Double queryLagSeconds1m() {
        try {
            Object r = entityManager.createNativeQuery("""
                    select extract(epoch from (current_timestamp - max(open_time)))
                    from marketdata_candles
                    where deleted = false and timeframe = '1m'
                    """).getSingleResult();
            if (r instanceof Number n) {
                return n.doubleValue();
            }
        } catch (DataAccessException | IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    /**
     * Approximate 1m candle write rate from the central store (not vendor websocket packets/sec).
     */
    private Double candles1mPerMinuteApprox() {
        try {
            Object r = entityManager.createNativeQuery("""
                    select count(*) from marketdata_candles
                    where deleted = false and timeframe = '1m'
                      and open_time >= (current_timestamp - interval '5 minutes')
                    """).getSingleResult();
            if (r instanceof Number n) {
                return n.doubleValue() / 5.0;
            }
        } catch (DataAccessException | IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private long queryLong(String sql) {
        try {
            Object r = entityManager.createNativeQuery(sql).getSingleResult();
            if (r instanceof Number n) {
                return n.longValue();
            }
        } catch (DataAccessException | IllegalArgumentException ignored) {
            return -1L;
        }
        return -1L;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> worstSymbolsSample() {
        try {
            List<Object[]> rows = entityManager.createNativeQuery("""
                    select symbol, max(open_time) as mx
                    from marketdata_candles
                    where deleted = false and timeframe = '1m'
                    group by symbol
                    order by mx asc
                    limit 8
                    """).getResultList();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] row : rows) {
                if (row == null || row.length < 2) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("symbol", row[0] != null ? row[0].toString() : "");
                one.put("latestOpenTime", row[1] != null ? row[1].toString() : null);
                out.add(one);
            }
            return out;
        } catch (DataAccessException | IllegalArgumentException e) {
            return List.of();
        }
    }
}
