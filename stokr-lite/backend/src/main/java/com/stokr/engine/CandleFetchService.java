package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleFetchService {

    private final CandleDataRepository candleRepository;

    public List<CandleData> fetchCandles(String symbol, String timeframe, Instant startTime, Instant endTime) {
        log.info("Fetching candles: symbol={}, timeframe={}, start={}, end={}", symbol, timeframe, startTime, endTime);

        // 1. Check database first
        List<CandleData> dbCandles = candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symbol, timeframe, startTime, endTime);

        if (!dbCandles.isEmpty()) {
            log.info("Found {} candles in DB for {}/{}", dbCandles.size(), symbol, timeframe);
            return dbCandles;
        }

        log.warn("No candles in DB, would fetch from Chartink/Zerodha (implement these methods)");

        // TODO: 2. Try Chartink API
        // List<CandleData> chartinkCandles = fetchFromChartink(symbol, timeframe, startTime, endTime);
        // if (!chartinkCandles.isEmpty()) {
        //     saveCandles(chartinkCandles);
        //     return chartinkCandles;
        // }

        // TODO: 3. Try Zerodha API with auth handling
        // List<CandleData> zerodhaCandles = fetchFromZerodha(symbol, timeframe, startTime, endTime);
        // if (!zerodhaCandles.isEmpty()) {
        //     saveCandles(zerodhaCandles);
        //     return zerodhaCandles;
        // }

        log.error("Could not fetch candles from any source");
        return Collections.emptyList();
    }

    public void saveCandles(List<CandleData> candles) {
        log.info("Saving {} candles to database", candles.size());
        candleRepository.saveAll(candles);
    }

    public List<CandleData> getCandles(String symbol, String timeframe, Instant startTime, Instant endTime) {
        return candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symbol, timeframe, startTime, endTime);
    }

    // Helper: Generate mock candles for testing
    public List<CandleData> generateMockCandles(String symbol, String timeframe, Instant startTime, Instant endTime) {
        List<CandleData> candles = new ArrayList<>();
        BigDecimal currentPrice = new BigDecimal("100.00");
        Instant current = startTime;
        long intervalMillis = getIntervalMillis(timeframe);

        while (current.isBefore(endTime)) {
            BigDecimal open = currentPrice;
            BigDecimal close = currentPrice.add(new BigDecimal(Math.random() * 2 - 1));
            BigDecimal high = open.max(close).add(new BigDecimal(Math.random() * 0.5));
            BigDecimal low = open.min(close).subtract(new BigDecimal(Math.random() * 0.5));

            CandleData candle = new CandleData();
            candle.setSymbol(symbol);
            candle.setTimeframe(timeframe);
            candle.setTimestamp(current);
            candle.setOpen(open);
            candle.setHigh(high);
            candle.setLow(low);
            candle.setClose(close);
            candle.setVolume(1000000L);

            candles.add(candle);
            currentPrice = close;
            current = current.plusMillis(intervalMillis);
        }

        return candles;
    }

    private long getIntervalMillis(String timeframe) {
        return switch (timeframe) {
            case "1min" -> 60_000;
            case "5min" -> 300_000;
            case "15min" -> 900_000;
            case "hourly", "1hour" -> 3_600_000;
            case "daily" -> 86_400_000;
            default -> 60_000;
        };
    }
}
