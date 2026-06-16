package com.stokr.marketdata.seed;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Inserts deterministic 1m synthetic OHLCV for {@code NIFTY 50} when the DB has no replay-grade history.
 * <p>Disabled in production via {@code stokr.replay.seed-synthetic-candles=false}. Docker dev defaults enable it.</p>
 */
@Component
@Order(50)
@ConditionalOnProperty(name = "stokr.replay.seed-synthetic-candles", havingValue = "true")
@Slf4j
public class ReplaySyntheticCandleSeeder implements ApplicationRunner {

    private static final String SYMBOL = "NIFTY 50";
    private static final String TF = "1m";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = countExisting();
        if (existing >= 5_000L) {
            log.info("replay.seed.skip symbol={} tf={} existingBars={}", SYMBOL, TF, existing);
            return;
        }
        Instant end = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(45, ChronoUnit.DAYS);
        Timestamp nowTs = Timestamp.from(Instant.now());
        String sql =
                """
                INSERT INTO marketdata_candles (id, created_at, updated_at, version, deleted, symbol, timeframe, open_time, open_price, high_price, low_price, close_price, volume)
                SELECT gen_random_uuid(), :now, :now, 0, false, :symbol, :tf, gs.ts,
                       (22000 + 120 * sin(gs.i / 400.0))::numeric(24,8),
                       (22006 + 120 * sin(gs.i / 400.0))::numeric(24,8),
                       (21994 + 120 * sin(gs.i / 400.0))::numeric(24,8),
                       (22000 + 120 * sin((gs.i + 1) / 400.0))::numeric(24,8),
                       (2000000 + (gs.i % 900000))::numeric(24,8)
                FROM (
                    SELECT t.ts AS ts, row_number() OVER (ORDER BY t.ts) - 1 AS i
                    FROM generate_series(:start, :end, interval '1 minute') AS t(ts)
                ) gs
                ON CONFLICT (symbol, timeframe, open_time) WHERE deleted = false DO NOTHING
                """;
        int inserted =
                entityManager
                        .createNativeQuery(sql)
                        .setParameter("now", nowTs)
                        .setParameter("symbol", SYMBOL)
                        .setParameter("tf", TF)
                        .setParameter("start", Timestamp.from(start))
                        .setParameter("end", Timestamp.from(end))
                        .executeUpdate();
        long after = countExisting();
        log.info(
                "replay.seed.done symbol={} tf={} window={}..{} insertedOrSkippedRows={} totalBarsApprox={}",
                SYMBOL,
                TF,
                start,
                end,
                inserted,
                after
        );
    }

    private long countExisting() {
        Object one =
                entityManager
                        .createQuery(
                                "select count(c) from MarketdataCandle c where c.symbol = :s and c.timeframe = :t and c.deleted = false"
                        )
                        .setParameter("s", SYMBOL)
                        .setParameter("t", TF)
                        .getSingleResult();
        if (one instanceof Long l) {
            return l;
        }
        return ((Number) one).longValue();
    }
}
