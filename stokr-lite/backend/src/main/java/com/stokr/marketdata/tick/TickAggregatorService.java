package com.stokr.marketdata.tick;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Aggregates tick data into true 1-min OHLC candles and persists them.
 * Also maintains intra-minute state for anomaly detection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TickAggregatorService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbcTemplate;
    private final TickDataRepository tickDataRepo;
    private final AnomalyDetectionService anomalyDetector;

    // Intra-minute aggregation state: symbol -> MinuteAggState
    private final Map<String, MinuteAggState> minuteStates = new ConcurrentHashMap<>();

    // Previous tick LTP for volume delta
    private final Map<String, Long> prevTotalVolume = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> prevLtp = new ConcurrentHashMap<>();
    // Rolling windows for anomaly detection
    final Map<String, RollingWindow> volumeWindows = new ConcurrentHashMap<>();
    final Map<String, RollingWindow> priceWindows = new ConcurrentHashMap<>();

    static class MinuteAggState {
        final String symbol;
        final LocalDateTime minuteStart;
        BigDecimal open, high, low, close;
        long volume, totalVolume;
        int tradeCount;
        long buyVolume, sellVolume;

        MinuteAggState(String symbol, LocalDateTime minuteStart, BigDecimal firstLtp, long totalVol) {
            this.symbol = symbol;
            this.minuteStart = minuteStart;
            this.open = firstLtp;
            this.high = firstLtp;
            this.low = firstLtp;
            this.close = firstLtp;
            this.volume = 0;
            this.totalVolume = totalVol;
            this.tradeCount = 1;
        }

        synchronized void update(BigDecimal ltp, long totalVol, long buyQty, long sellQty) {
            long volDelta = Math.max(0, totalVol - this.totalVolume);
            this.totalVolume = totalVol;
            if (volDelta == 0) return;
            this.close = ltp;
            this.volume += volDelta;
            this.tradeCount++;
            this.buyVolume += buyQty;
            this.sellVolume += sellQty;
            if (ltp.compareTo(high) > 0) this.high = ltp;
            if (ltp.compareTo(low) < 0) this.low = ltp;
        }

        TickCandleData toCandle() {
            var c = new TickCandleData();
            c.setSymbol(symbol);
            c.setTimeframe("1min");
            c.setTimestamp(minuteStart);
            c.setOpen(open);
            c.setHigh(high);
            c.setLow(low);
            c.setClose(close);
            c.setVolume(volume);
            c.setTradeCount(tradeCount);
            c.setCreatedAt(Instant.now());
            return c;
        }
    }

    public static class RollingWindow {
        final int size;
        final LinkedList<Double> values = new LinkedList<>();

        public RollingWindow(int size) { this.size = size; }

        public synchronized void add(double v) {
            values.addLast(v);
            if (values.size() > size) values.removeFirst();
        }

        public synchronized double mean() {
            if (values.isEmpty()) return 0;
            return values.stream().mapToDouble(d -> d).average().orElse(0);
        }

        public synchronized double stddev() {
            if (values.size() < 2) return 0;
            double m = mean();
            double v = values.stream().mapToDouble(d -> (d - m) * (d - m)).average().orElse(0);
            return Math.sqrt(v);
        }

        public synchronized double latest() {
            return values.isEmpty() ? 0 : values.getLast();
        }

        public synchronized int size() { return values.size(); }
    }

    @PostConstruct
    public void init() {
        log.info("TickAggregatorService started");
    }

    /**
     * Called on each tick from the WebSocket client.
     */
    public void onTick(TickData tick) {
        String symbol = tick.getSymbol();
        BigDecimal ltp = tick.getLtp();
        long totalVol = tick.getVolume();
        long buyQty = tick.getBuyQuantity();
        long sellQty = tick.getSellQuantity();

        // Track rolling windows for anomaly detection
        if (prevLtp.containsKey(symbol)) {
            BigDecimal prev = prevLtp.get(symbol);
            if (prev.compareTo(BigDecimal.ZERO) > 0) {
                double pctChange = ltp.subtract(prev).divide(prev, 6, java.math.RoundingMode.HALF_UP).doubleValue();
                priceWindows.computeIfAbsent(symbol, k -> new RollingWindow(20)).add(pctChange);
            }
        }
        prevLtp.put(symbol, ltp);

        long prevTot = prevTotalVolume.getOrDefault(symbol, 0L);
        long deltaVol = Math.max(0, totalVol - prevTot);
        prevTotalVolume.put(symbol, totalVol);

        if (deltaVol > 0) {
            volumeWindows.computeIfAbsent(symbol, k -> new RollingWindow(20)).add((double) deltaVol);
        }

        // Update current minute aggregation
        LocalDateTime minuteKey = LocalDateTime.now(IST).withSecond(0).withNano(0);
        MinuteAggState state = minuteStates.computeIfAbsent(symbol,
            k -> new MinuteAggState(symbol, minuteKey, ltp, totalVol));
        state.update(ltp, totalVol, buyQty, sellQty);

        // Run anomaly detection on this tick
        anomalyDetector.evaluate(tick, this);
    }

    /**
     * Every 15 seconds, flush partial candle stats for sub-minute anomaly signals.
     */
    @Scheduled(fixedRate = 15_000)
    public void flushPartialStats() {
        // Trigger anomaly re-evaluation with current sub-minute aggregates
        for (Map.Entry<String, MinuteAggState> e : minuteStates.entrySet()) {
            String symbol = e.getKey();
            MinuteAggState state = e.getValue();
            anomalyDetector.evaluatePartial(symbol, state, this);
        }
    }

    /**
     * Every minute at :00, finalize the tick-based candle and upsert it.
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void finalizeMinuteCandle() {
        LocalDateTime finalizedMinute = LocalDateTime.now(IST).minusMinutes(1).withSecond(0).withNano(0);
        List<TickCandleData> candles = new ArrayList<>();

        for (Map.Entry<String, MinuteAggState> e : minuteStates.entrySet()) {
            String symbol = e.getKey();
            MinuteAggState state = e.getValue();
            if (!state.minuteStart.equals(finalizedMinute)) continue;

            TickCandleData candle = state.toCandle();
            candles.add(candle);

            // Reset state for next minute
            minuteStates.remove(symbol);
        }

        if (!candles.isEmpty()) {
            batchUpsertCandles(candles);
            log.debug("TickAggregator: upserted {} tick-based candles for {}", candles.size(), finalizedMinute);
        }
    }

    private void batchUpsertCandles(List<TickCandleData> candles) {
        String sql =
            "INSERT INTO tick_candle_data (symbol, timeframe, \"timestamp\", \"open\", high, low, \"close\", volume, trade_count, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (symbol, timeframe, \"timestamp\") DO UPDATE SET " +
            "  high    = GREATEST(tick_candle_data.high, EXCLUDED.high), " +
            "  low     = LEAST(tick_candle_data.low,    EXCLUDED.low), " +
            "  \"close\" = EXCLUDED.\"close\", " +
            "  volume  = CASE WHEN EXCLUDED.volume > tick_candle_data.volume THEN EXCLUDED.volume ELSE tick_candle_data.volume END, " +
            "  trade_count = tick_candle_data.trade_count + EXCLUDED.trade_count";

        List<Object[]> batchArgs = candles.stream()
            .map(c -> new Object[]{
                c.getSymbol(), c.getTimeframe(), c.getTimestamp(),
                c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                c.getVolume(), c.getTradeCount()
            }).toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    public BigDecimal getCurrentLtp(String symbol) {
        return prevLtp.getOrDefault(symbol, BigDecimal.ZERO);
    }

    public RollingWindow getVolumeWindow(String symbol) {
        return volumeWindows.get(symbol);
    }

    public RollingWindow getPriceWindow(String symbol) {
        return priceWindows.get(symbol);
    }

    public long getCurrentVolume(String symbol) {
        MinuteAggState s = minuteStates.get(symbol);
        return s != null ? s.volume : 0;
    }
}
