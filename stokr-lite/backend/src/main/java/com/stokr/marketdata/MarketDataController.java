package com.stokr.marketdata;

import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final CandleDataRepository candleRepo;
    private final Universe universe;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static class TickerItem {
        final String symbol;
        final String label;
        final BigDecimal fallbackLtp;
        TickerItem(String s, String l, String f) {
            this.symbol = s;
            this.label = l;
            this.fallbackLtp = new BigDecimal(f);
        }
    }

    private static final List<TickerItem> TICKER_ITEMS = List.of(
        new TickerItem("NIFTY50", "NIFTY 50", "25340.50"),
        new TickerItem("BANKNIFTY", "BANK NIFTY", "54210.00"),
        new TickerItem("FINNIFTY", "FINNIFTY", "24850.00"),
        new TickerItem("RELIANCE", "RELIANCE", "1241.90"),
        new TickerItem("HDFCBANK", "HDFC BANK", "1716.45"),
        new TickerItem("TCS", "TCS", "3820.00"),
        new TickerItem("INFY", "INFOSYS", "1853.80"),
        new TickerItem("ICICIBANK", "ICICI BANK", "1253.70"),
        new TickerItem("SBIN", "SBIN", "816.90"),
        new TickerItem("BHARTIARTL", "BHARTI AIRTEL", "1680.50"),
        new TickerItem("LT", "L&T", "3640.00")
    );

    @GetMapping("/ticker")
    public ResponseEntity<List<Map<String, Object>>> getTickerData() {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDateTime todayStart = LocalDateTime.now(IST).withHour(9).withMinute(15).withSecond(0).withNano(0);

        for (TickerItem item : TICKER_ITEMS) {
            String sym = item.symbol;
            BigDecimal ltp = marketDataService.getLtp(sym);
            BigDecimal openPrice = BigDecimal.ZERO;

            if (ltp.compareTo(BigDecimal.ZERO) == 0) {
                List<CandleData> candles = candleRepo.findBySymbolAndTimeframeOrderByTimestampDesc(sym, "1min");
                if (!candles.isEmpty()) {
                    ltp = candles.get(0).getClose();
                    openPrice = candles.get(candles.size() - 1).getOpen();
                } else {
                    ltp = item.fallbackLtp;
                }
            }

            if (openPrice.compareTo(BigDecimal.ZERO) == 0) {
                List<CandleData> dayCandles = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                    sym, "1min", todayStart, LocalDateTime.now(IST));
                if (!dayCandles.isEmpty()) {
                    openPrice = dayCandles.get(0).getOpen();
                } else {
                    openPrice = ltp.multiply(new BigDecimal("0.996"));
                }
            }

            BigDecimal diff = ltp.subtract(openPrice);
            BigDecimal pct = openPrice.compareTo(BigDecimal.ZERO) > 0
                ? diff.divide(openPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("symbol", sym);
            map.put("label", item.label);
            map.put("price", ltp.setScale(2, RoundingMode.HALF_UP));
            map.put("change", diff.setScale(2, RoundingMode.HALF_UP));
            map.put("percentChange", pct.setScale(2, RoundingMode.HALF_UP));
            map.put("isUp", diff.compareTo(BigDecimal.ZERO) >= 0);
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/candles/{symbol}")
    public ResponseEntity<List<Candle>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5min") String interval,
            @RequestParam(defaultValue = "100") int count) {
        return ResponseEntity.ok(marketDataService.getCandles(symbol, interval, count));
    }

    @GetMapping("/ltp/{symbol}")
    public ResponseEntity<Map<String, BigDecimal>> getLtp(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("ltp", marketDataService.getLtp(symbol)));
    }

    @GetMapping("/ltp/batch")
    public ResponseEntity<Map<String, BigDecimal>> getLtpBatch(@RequestParam List<String> symbols) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String sym : symbols) {
            result.put(sym.toUpperCase(), marketDataService.getLtp(sym));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/universe")
    public ResponseEntity<List<String>> getUniverse() {
        return ResponseEntity.ok(universe.getSymbols());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> marketStatus() {
        return ResponseEntity.ok(Map.of(
                "isOpen", marketDataService.isMarketOpen()
        ));
    }
}
