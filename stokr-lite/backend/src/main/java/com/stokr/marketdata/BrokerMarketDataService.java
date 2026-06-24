package com.stokr.marketdata;

import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MarketDataService backed by Zerodha live quotes (via ZerodhaLiveDataScheduler)
 * and the local candle_data DB table.
 *
 * LTP:     from in-memory cache updated every minute by the scheduler
 *          → falls back to last DB close if cache is empty
 * Candles: read from candle_data DB table populated every minute by the scheduler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerMarketDataService implements MarketDataService {

    private final ZerodhaLiveDataScheduler liveData;
    private final CandleDataRepository     candleRepo;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Override
    public BigDecimal getLtp(String symbol) {
        String sym = symbol.toUpperCase();
        BigDecimal cached = liveData.getLtp(sym);
        if (cached.compareTo(BigDecimal.ZERO) > 0) return cached;

        // Fallback: last stored close from DB
        List<CandleData> rows = candleRepo.findBySymbolAndTimeframeOrderByTimestampDesc(sym, "1min");
        if (!rows.isEmpty()) {
            log.debug("getLtp({}) using DB fallback", sym);
            return rows.get(0).getClose();
        }
        return BigDecimal.ZERO;
    }

    @Override
    public List<Candle> getCandles(String symbol, String interval, int count) {
        String sym = symbol.toUpperCase();
        Instant from = Instant.now().minus(count + 10, java.time.temporal.ChronoUnit.MINUTES);
        List<CandleData> rows = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            sym, "1min", from, Instant.now());
        if (rows.isEmpty()) return Collections.emptyList();
        int start = Math.max(0, rows.size() - count);
        return toCandles(sym, rows.subList(start, rows.size()));
    }

    @Override
    public List<Candle> getCandlesBetween(String symbol, String interval,
                                           LocalDateTime from, LocalDateTime to) {
        String sym = symbol.toUpperCase();
        Instant fromI = from.atZone(IST).toInstant();
        Instant toI   = to.atZone(IST).toInstant();
        List<CandleData> rows = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            sym, "1min", fromI, toI);
        return toCandles(sym, rows);
    }

    @Override
    public boolean isMarketOpen() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 30));
    }

    private List<Candle> toCandles(String symbol, List<CandleData> rows) {
        return rows.stream()
            .map(r -> new Candle(
                symbol,
                LocalDateTime.ofInstant(r.getTimestamp(), IST),
                r.getOpen(), r.getHigh(), r.getLow(), r.getClose(), r.getVolume()))
            .collect(Collectors.toList());
    }
}
