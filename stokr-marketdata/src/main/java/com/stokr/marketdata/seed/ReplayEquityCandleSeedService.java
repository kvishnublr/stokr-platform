package com.stokr.marketdata.seed;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Seeds deterministic 1m OHLCV for equity symbols that lack replay-grade history.
 * Used by admin signal pipeline activation so catalog/replay strategies can evaluate.
 */
@Service
@Slf4j
public class ReplayEquityCandleSeedService {

    private static final String TF = "1m";
    private static final long MIN_BARS_TO_SKIP = 500L;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.replay.equity-seed.session-start:09:15}")
    private LocalTime sessionStart;

    @Value("${stokr.replay.equity-seed.session-end:15:30}")
    private LocalTime sessionEnd;

    @Value("${stokr.replay.equity-seed.trading-days:5}")
    private int tradingDays;

    @Transactional
    public Map<String, Object> seedSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of("seeded", 0, "skipped", 0, "message", "no symbols");
        }
        Instant end = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant start = sessionStartOnTradingDay(end, tradingDays);
        Timestamp nowTs = Timestamp.from(Instant.now());

        int seeded = 0;
        int skipped = 0;
        List<String> seededSymbols = new ArrayList<>();

        for (String raw : symbols) {
            String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) {
                continue;
            }
            long existing = countExisting(symbol);
            if (existing >= MIN_BARS_TO_SKIP) {
                skipped++;
                continue;
            }
            int rows = insertSynthetic(symbol, start, end, nowTs);
            seeded++;
            seededSymbols.add(symbol);
            log.info("replay.equity_seed symbol={} insertedRows={} priorBars={}", symbol, rows, existing);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seeded", seeded);
        out.put("skipped", skipped);
        out.put("timeframe", TF);
        out.put("windowStart", start.toString());
        out.put("windowEnd", end.toString());
        out.put("seededSymbols", seededSymbols.size() <= 20 ? seededSymbols : seededSymbols.subList(0, 20));
        return out;
    }

    private Instant sessionStartOnTradingDay(Instant end, int days) {
        LocalDate d = end.atZone(zone).toLocalDate();
        int found = 0;
        while (found < days) {
            d = d.minusDays(1);
            if (d.getDayOfWeek().getValue() <= 5) {
                found++;
            }
        }
        return d.atTime(sessionStart).atZone(zone).toInstant();
    }

    private int insertSynthetic(String symbol, Instant start, Instant end, Timestamp nowTs) {
        String sql =
                """
                INSERT INTO marketdata_candles (id, created_at, updated_at, version, deleted, symbol, timeframe, open_time, open_price, high_price, low_price, close_price, volume)
                SELECT gen_random_uuid(), :now, :now, 0, false, :symbol, :tf, gs.ts,
                       (100 + 8 * sin(gs.i / 50.0 + ascii(substr(:symbol,1,1)) / 90.0))::numeric(24,8),
                       (104 + 8 * sin(gs.i / 50.0 + ascii(substr(:symbol,1,1)) / 90.0))::numeric(24,8),
                       (96 + 8 * sin(gs.i / 50.0 + ascii(substr(:symbol,1,1)) / 90.0))::numeric(24,8),
                       (100 + 8 * sin((gs.i + 1) / 50.0 + ascii(substr(:symbol,1,1)) / 90.0))::numeric(24,8),
                       (500000 + (gs.i % 200000))::numeric(24,8)
                FROM (
                    SELECT t.ts AS ts, row_number() OVER (ORDER BY t.ts) - 1 AS i
                    FROM generate_series(:start, :end, interval '1 minute') AS t(ts)
                    WHERE (t.ts AT TIME ZONE 'Asia/Kolkata')::time >= :sessStart::time
                      AND (t.ts AT TIME ZONE 'Asia/Kolkata')::time <= :sessEnd::time
                      AND extract(dow from (t.ts AT TIME ZONE 'Asia/Kolkata')) BETWEEN 1 AND 5
                ) gs
                ON CONFLICT (symbol, timeframe, open_time) WHERE deleted = false DO NOTHING
                """;
        return entityManager
                .createNativeQuery(sql)
                .setParameter("now", nowTs)
                .setParameter("symbol", symbol)
                .setParameter("tf", TF)
                .setParameter("start", Timestamp.from(start))
                .setParameter("end", Timestamp.from(end))
                .setParameter("sessStart", sessionStart.toString())
                .setParameter("sessEnd", sessionEnd.toString())
                .executeUpdate();
    }

    private long countExisting(String symbol) {
        Object one =
                entityManager
                        .createQuery(
                                "select count(c) from MarketdataCandle c where c.symbol = :s and c.timeframe = :t and c.deleted = false"
                        )
                        .setParameter("s", symbol)
                        .setParameter("t", TF)
                        .getSingleResult();
        if (one instanceof Long l) {
            return l;
        }
        return ((Number) one).longValue();
    }
}
