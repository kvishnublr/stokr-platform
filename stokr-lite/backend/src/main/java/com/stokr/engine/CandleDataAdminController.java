package com.stokr.engine;

import com.stokr.external.ZerodhaCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/admin/candles")
@RequiredArgsConstructor
public class CandleDataAdminController {

    private final CandleDataRepository candleRepository;
    private final ZerodhaCandleService zerodhaCandleService;

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

    @PostMapping("/load-zerodha")
    public ResponseEntity<Map<String, Object>> loadFromZerodha(
            @RequestParam(defaultValue = "60") int days,
            @RequestParam(defaultValue = "1min") String timeframe,
            @RequestBody(required = false) List<String> symbols) {

        if (symbols == null || symbols.isEmpty()) {
            symbols = getNifty100Symbols();
        }

        Instant end = Instant.now();
        Instant start = end.minus(days, ChronoUnit.DAYS);

        log.info("Bulk loading {} symbols × {} days of {} data from Zerodha", symbols.size(), days, timeframe);

        AtomicInteger loaded = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        List<String> failed = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                List<CandleData> candles = zerodhaCandleService.fetchCandles(symbol, timeframe, start, end);
                if (candles.isEmpty()) {
                    log.warn("No data for {}", symbol);
                    skipped.incrementAndGet();
                } else {
                    candleRepository.saveAll(candles);
                    loaded.addAndGet(candles.size());
                    log.info("Loaded {} candles for {}", candles.size(), symbol);
                }
            } catch (Exception e) {
                log.error("Failed to load {}: {}", symbol, e.getMessage());
                failed.add(symbol);
            }
        }

        return ResponseEntity.ok(Map.of(
            "status", "DONE",
            "symbolsRequested", symbols.size(),
            "candlesLoaded", loaded.get(),
            "symbolsSkipped", skipped.get(),
            "failedSymbols", failed,
            "timeframe", timeframe,
            "days", days
        ));
    }

    private List<String> getNifty100Symbols() {
        return List.of(
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","INFY","HINDUNILVR","ITC","KOTAKBANK","LT","SBIN",
            "AXISBANK","BAJFINANCE","BHARTIARTL","TITAN","MARUTI","HCLTECH","SUNPHARMA","TATAMOTORS","NTPC","BAJAJFINSV",
            "WIPRO","JSWSTEEL","ONGC","POWERGRID","COALINDIA","GRASIM","TATASTEEL","BPCL","HINDALCO","ULTRACEMCO",
            "ADANIENT","ADANIPORTS","APOLLOHOSP","DIVISLAB","DRREDDY","EICHERMOT","HDFCLIFE","HEROMOTOCO","INDUSINDBK","M&M",
            "NESTLEIND","SBILIFE","TATACONSUM","TECHM","TRENT","DMART","UPL","CIPLA","BRITANNIA","ASIANPAINT",
            "BERGEPAINT","CANBK","DABUR","GODREJCP","HAL","HAVELLS","IOB","IRCTC","LICI","MCDOWELL",
            "PIDILITIND","POLYCAB","SIEMENS","ZOMATO","AMBUJACEM","ATGL","BANDHANBNK","BANKBARODA","BEL","CHOLAFIN",
            "COFORGE","COLPAL","DALBHARAT","FEDERALBNK","GAIL","GODREJPROP","IDFCFIRSTB","INDUSTOWER","IRFC","JUBLFOOD",
            "KALYANKJIL","LALPATHLAB","LODHA","LTTS","LUPIN","MFSL","NHPC","NYKAA","OFSS","PAGEIND",
            "PAYTM","PERSISTENT","RECLTD","SAIL","SHREECEM","TORNTPHARM","TVSMOTOR","VBL","VEDL","VOLTAS"
        );
    }
}
