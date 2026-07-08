package com.stokr.engine;

import com.stokr.external.ZerodhaCandleService;
import com.stokr.strategy.UniverseGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/candles")
@RequiredArgsConstructor
public class CandleDataAdminController {

    private final CandleDataRepository candleRepository;
    private final ZerodhaCandleService zerodhaCandleService;
    private final UniverseGroupService universeGroupService;
    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Smart backfill: for each symbol in the universe, check how many 1-min candles exist
     * for the requested date range. If already "complete" (≥ 300 candles/trading day), skip.
     * Otherwise fetch from Zerodha and upsert (safe to re-run).
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam String dateStart,
            @RequestParam String dateEnd,
            @RequestParam(defaultValue = "NIFTY_100") String universe,
            @RequestParam(defaultValue = "1min") String timeframe) {

        LocalDateTime start = LocalDate.parse(dateStart, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        LocalDateTime end   = LocalDate.parse(dateEnd,   DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59);

        List<String> symbols = resolveSymbols(universe);
        long tradingDays = countWeekdays(start.toLocalDate(), end.toLocalDate());
        long minComplete = "daily".equalsIgnoreCase(timeframe) || "weekly".equalsIgnoreCase(timeframe)
            ? tradingDays  // 1 candle per day for daily/weekly
            : tradingDays * 300L; // 300 1-min candles per trading day minimum

        log.info("Backfill: universe={} symbols={} range={} to {} tradingDays={} minComplete={}",
            universe, symbols.size(), dateStart, dateEnd, tradingDays, minComplete);

        int candlesSaved  = 0;
        int symbolsSkipped = 0;
        List<String> failed = new ArrayList<>();

        // Sequential with 400ms delay — Zerodha rate-limits at ~3 req/sec; 4-thread parallel causes 429
        for (String symbol : symbols) {
            try {
                long existing = candleRepository.countBySymbolAndTimeframeAndTimestampBetween(
                    symbol, timeframe, start, end);

                if (existing >= minComplete) {
                    log.debug("{} already has {} candles — skipping", symbol, existing);
                    symbolsSkipped++;
                    continue;
                }

                log.info("{}: {} candles in DB, fetching from Zerodha...", symbol, existing);
                List<CandleData> candles = zerodhaCandleService.fetchCandles(symbol, timeframe, start, end);
                if (!candles.isEmpty()) {
                    upsertCandles(candles);
                    candlesSaved += candles.size();
                    log.info("{}: saved {} candles", symbol, candles.size());
                } else {
                    log.warn("{}: Zerodha returned 0 candles", symbol);
                    failed.add(symbol);
                }
                Thread.sleep(400); // respect Zerodha rate limit
            } catch (Exception e) {
                log.error("Backfill failed for {}: {}", symbol, e.getMessage());
                failed.add(symbol);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "DONE");
        result.put("universe", universe);
        result.put("dateRange", dateStart + " to " + dateEnd);
        result.put("tradingDays", tradingDays);
        result.put("symbolsTotal", symbols.size());
        result.put("symbolsSkipped", symbolsSkipped);
        result.put("symbolsFailed", failed.size());
        result.put("candlesSaved", candlesSaved);
        result.put("failedSymbols", failed);
        log.info("Backfill complete: {}", result);
        return ResponseEntity.ok(result);
    }

    /** Check DB coverage for a universe + date range without fetching anything. */
    @GetMapping("/coverage")
    public ResponseEntity<Map<String, Object>> coverage(
            @RequestParam String dateStart,
            @RequestParam String dateEnd,
            @RequestParam(defaultValue = "NIFTY_100") String universe,
            @RequestParam(defaultValue = "1min") String timeframe) {

        LocalDateTime start = LocalDate.parse(dateStart, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        LocalDateTime end   = LocalDate.parse(dateEnd,   DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59);
        List<String> symbols = resolveSymbols(universe);
        long tradingDays = countWeekdays(start.toLocalDate(), end.toLocalDate());
        long minComplete = tradingDays * 300L;

        int complete = 0, partial = 0, missing = 0;
        List<String> missingSymbols = new ArrayList<>();

        for (String symbol : symbols) {
            long count = candleRepository.countBySymbolAndTimeframeAndTimestampBetween(symbol, timeframe, start, end);
            if (count >= minComplete)        complete++;
            else if (count > 0)             { partial++; missingSymbols.add(symbol + "(" + count + ")"); }
            else                            { missing++;  missingSymbols.add(symbol); }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("universe", universe);
        result.put("dateRange", dateStart + " to " + dateEnd);
        result.put("tradingDays", tradingDays);
        result.put("symbolsTotal", symbols.size());
        result.put("complete", complete);
        result.put("partial", partial);
        result.put("missing", missing);
        result.put("missingOrPartialSymbols", missingSymbols);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/flush")
    public ResponseEntity<Map<String, Object>> flushAll(
            @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            long count = candleRepository.count();
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Add ?confirm=true to delete all " + count + " candles",
                "count", count
            ));
        }
        long count = candleRepository.count();
        candleRepository.deleteAll();
        log.info("Flushed all {} candles from DB", count);
        return ResponseEntity.ok(Map.of("deleted", count, "status", "OK"));
    }

    private void upsertCandles(List<CandleData> candles) {
        String sql =
            "INSERT INTO candle_data (symbol, timeframe, \"timestamp\", \"open\", high, low, \"close\", volume, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
            "ON CONFLICT (symbol, timeframe, \"timestamp\") DO UPDATE SET " +
            "  high    = GREATEST(candle_data.high, EXCLUDED.high), " +
            "  low     = LEAST(candle_data.low, EXCLUDED.low), " +
            "  \"close\" = EXCLUDED.\"close\", " +
            "  volume  = EXCLUDED.volume";

        List<Object[]> batchArgs = new ArrayList<>();
        for (CandleData c : candles) {
            batchArgs.add(new Object[]{
                c.getSymbol(), c.getTimeframe(), c.getTimestamp(),
                c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume()
            });
        }
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    private List<String> resolveSymbols(String universe) {
        if ("NIFTY_500".equalsIgnoreCase(universe)) {
            return com.stokr.marketdata.ZerodhaLiveDataScheduler.NIFTY_500;
        }
        return universeGroupService.findByKey(universe)
            .map(g -> universeGroupService.resolveSymbolsForGroup(g.getId()))
            .orElseThrow(() -> new IllegalArgumentException("Unknown universe: " + universe));
    }

    private long countWeekdays(LocalDate start, LocalDate end) {
        long count = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }
}
