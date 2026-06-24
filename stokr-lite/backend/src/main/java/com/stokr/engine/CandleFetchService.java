package com.stokr.engine;

import com.stokr.external.ChartinkCandleService;
import com.stokr.external.ZerodhaCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleFetchService {

    private final CandleDataRepository candleRepository;
    private final ChartinkCandleService chartinkCandleService;
    private final ZerodhaCandleService zerodhaCandleService;

    private static final List<String> TIMEFRAME_FALLBACK_ORDER = List.of(
        "1min", "5min", "15min", "hourly", "daily"
    );

    public List<CandleData> fetchCandles(String symbol, String timeframe, LocalDateTime startTime, LocalDateTime endTime) {
        log.info("Fetching candles: symbol={}, timeframe={}, start={}, end={}", symbol, timeframe, startTime, endTime);

        // 1. Check database for exact timeframe first, then fall back to coarser timeframes
        int requestedIdx = TIMEFRAME_FALLBACK_ORDER.indexOf(timeframe);
        List<String> toTry = requestedIdx >= 0
            ? TIMEFRAME_FALLBACK_ORDER.subList(requestedIdx, TIMEFRAME_FALLBACK_ORDER.size())
            : List.of(timeframe);

        for (String tf : toTry) {
            List<CandleData> dbCandles = candleRepository
                .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symbol, tf, startTime, endTime);
            if (!dbCandles.isEmpty()) {
                if (!tf.equals(timeframe)) {
                    log.info("No {} data in DB for {}, using {} data instead ({} candles)", timeframe, symbol, tf, dbCandles.size());
                } else {
                    log.info("Found {} candles in DB for {}/{}", dbCandles.size(), symbol, tf);
                }
                return dbCandles;
            }
        }

        log.info("No candles in DB for any timeframe, fetching from external sources...");

        // 2. Try Chartink API
        List<CandleData> chartinkCandles = chartinkCandleService.fetchCandles(symbol, timeframe, startTime, endTime);
        if (!chartinkCandles.isEmpty()) {
            log.info("Fetched {} candles from Chartink", chartinkCandles.size());
            saveCandles(chartinkCandles);
            return chartinkCandles;
        }

        // 3. Try Zerodha API with auth handling
        List<CandleData> zerodhaCandles = zerodhaCandleService.fetchCandles(symbol, timeframe, startTime, endTime);
        if (!zerodhaCandles.isEmpty()) {
            log.info("Fetched {} candles from Zerodha", zerodhaCandles.size());
            saveCandles(zerodhaCandles);
            return zerodhaCandles;
        }

        log.warn("Could not fetch candles from any source, returning empty list");
        return Collections.emptyList();
    }

    public void saveCandles(List<CandleData> candles) {
        log.info("Saving {} candles to database", candles.size());
        candleRepository.saveAll(candles);
    }

    public List<CandleData> getCandles(String symbol, String timeframe, LocalDateTime startTime, LocalDateTime endTime) {
        return candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symbol, timeframe, startTime, endTime);
    }
}
