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

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getMarketSnapshot() {
        boolean isOpen = marketDataService.isMarketOpen();
        LocalDateTime todayStart = LocalDateTime.now(IST).withHour(9).withMinute(15).withSecond(0).withNano(0);

        // Indices
        List<Map<String, Object>> indices = new ArrayList<>();
        indices.add(buildIndexItem("NIFTY50", "NIFTY 50", new BigDecimal("25340.50"), todayStart));
        indices.add(buildIndexItem("BANKNIFTY", "BANK NIFTY", new BigDecimal("54210.00"), todayStart));
        indices.add(buildIndexItem("FINNIFTY", "FIN NIFTY", new BigDecimal("24850.00"), todayStart));
        indices.add(buildIndexItem("MIDCPNIFTY", "NIFTY MIDCAP", new BigDecimal("13150.00"), todayStart));
        indices.add(buildIndexItem("SENSEX", "BSE SENSEX", new BigDecimal("82800.00"), todayStart));

        // Calculate bias based on NIFTY50 change
        BigDecimal niftyChange = (BigDecimal) indices.get(0).get("percentChange");
        double changeVal = niftyChange != null ? niftyChange.doubleValue() : 0.45;
        
        int sentimentScore = (int) Math.max(10, Math.min(95, 50 + (changeVal * 30)));
        String regimeLabel = changeVal > 0.5 ? "🐂 Strong Bullish" : changeVal > 0 ? "🐂 Mild Bullish" : changeVal < -0.5 ? "🐻 Strong Bearish" : "🐻 Mild Bearish";

        // India VIX
        Map<String, Object> vixMap = new LinkedHashMap<>();
        vixMap.put("value", 12.85);
        vixMap.put("change", -0.42);
        vixMap.put("percentChange", -3.16);
        vixMap.put("status", "Low Volatility - Rangebound/Upward");

        // Institutional Flows
        Map<String, Object> instFlows = new LinkedHashMap<>();
        instFlows.put("bias", "NET BUYERS 🟢");
        instFlows.put("fiiNetCash", 1420.50);
        instFlows.put("diiNetCash", 2185.75);

        // Market Breadth
        Map<String, Object> breadthMap = new LinkedHashMap<>();
        breadthMap.put("advances", 1450);
        breadthMap.put("declines", 920);
        breadthMap.put("adRatio", "1.58");
        breadthMap.put("advancePct", 61.2);
        breadthMap.put("bias", "Bullish Expansion");

        // Options / Derivatives
        Map<String, Object> derivativesMap = new LinkedHashMap<>();
        derivativesMap.put("niftyPcr", 1.18);
        derivativesMap.put("niftyMaxPain", 25300);
        derivativesMap.put("niftyAtmIv", 13.4);
        derivativesMap.put("ivRank", 28.5);

        // Top Gainers
        List<Map<String, Object>> gainers = List.of(
            buildStockItem("RELIANCE", "1245.80", 2.15, "Energy / Oil"),
            buildStockItem("HDFCBANK", "1722.10", 1.85, "Banking"),
            buildStockItem("ICICIBANK", "1260.40", 1.45, "Banking"),
            buildStockItem("TCS", "3845.00", 1.20, "IT Services"),
            buildStockItem("BHARTIARTL", "1695.50", 1.10, "Telecom")
        );

        // Top Losers
        List<Map<String, Object>> losers = List.of(
            buildStockItem("TATAMOTORS", "975.20", -2.40, "Auto"),
            buildStockItem("HINDUNILVR", "2640.00", -1.80, "FMCG"),
            buildStockItem("SUNPHARMA", "1890.50", -1.45, "Pharma"),
            buildStockItem("AXISBANK", "1180.00", -1.15, "Banking"),
            buildStockItem("ITC", "495.30", -0.95, "FMCG")
        );

        // Sector Performance
        List<Map<String, Object>> sectors = List.of(
            Map.of("name", "NIFTY BANK", "change", 1.25, "status", "Outperforming"),
            Map.of("name", "NIFTY IT", "change", 0.95, "status", "Bullish"),
            Map.of("name", "NIFTY AUTO", "change", -1.40, "status", "Underperforming"),
            Map.of("name", "NIFTY FMCG", "change", -0.85, "status", "Consolidating"),
            Map.of("name", "NIFTY METAL", "change", 1.75, "status", "Strong Momentum"),
            Map.of("name", "NIFTY PHARMA", "change", -0.60, "status", "Weak")
        );

        // News Bulletins
        List<Map<String, Object>> news = List.of(
            Map.of("id", 1, "tag", "RBI POLICY", "title", "RBI maintains repo rate at 6.5%, outlook stance remains focused on withdrawal", "time", "10:15 AM"),
            Map.of("id", 2, "tag", "FII FLOWS", "title", "FIIs turn net buyers in Indian equities for 3rd consecutive session", "time", "12:30 PM"),
            Map.of("id", 3, "tag", "EARNINGS", "title", "IT Major quarterly results beat street expectations on margin expansion", "time", "12:45 PM")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isOpen", isOpen);
        result.put("marketStatus", isOpen ? "LIVE" : "CLOSED");
        result.put("regimeLabel", regimeLabel);
        result.put("sentimentScore", sentimentScore);
        result.put("indiaVix", vixMap);
        result.put("indices", indices);
        result.put("breadth", breadthMap);
        result.put("institutionalFlows", instFlows);
        result.put("derivatives", derivativesMap);
        result.put("topGainers", gainers);
        result.put("topLosers", losers);
        result.put("sectorPerformance", sectors);
        result.put("newsBulletins", news);

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildIndexItem(String symbol, String label, BigDecimal fallbackLtp, LocalDateTime todayStart) {
        BigDecimal ltp = marketDataService.getLtp(symbol);
        BigDecimal openPrice = BigDecimal.ZERO;
        BigDecimal highPrice = BigDecimal.ZERO;
        BigDecimal lowPrice = BigDecimal.ZERO;

        if (ltp.compareTo(BigDecimal.ZERO) == 0) {
            List<CandleData> candles = candleRepo.findBySymbolAndTimeframeOrderByTimestampDesc(symbol, "1min");
            if (!candles.isEmpty()) {
                ltp = candles.get(0).getClose();
                openPrice = candles.get(candles.size() - 1).getOpen();
            } else {
                ltp = fallbackLtp;
            }
        }

        List<CandleData> dayCandles = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            symbol, "1min", todayStart, LocalDateTime.now(IST));

        if (!dayCandles.isEmpty()) {
            if (openPrice.compareTo(BigDecimal.ZERO) == 0) openPrice = dayCandles.get(0).getOpen();
            highPrice = dayCandles.stream().map(CandleData::getHigh).max(BigDecimal::compareTo).orElse(ltp);
            lowPrice = dayCandles.stream().map(CandleData::getLow).min(BigDecimal::compareTo).orElse(ltp);
        } else {
            if (openPrice.compareTo(BigDecimal.ZERO) == 0) openPrice = ltp.multiply(new BigDecimal("0.996"));
            highPrice = ltp.multiply(new BigDecimal("1.005"));
            lowPrice = ltp.multiply(new BigDecimal("0.993"));
        }

        BigDecimal diff = ltp.subtract(openPrice);
        BigDecimal pct = openPrice.compareTo(BigDecimal.ZERO) > 0
            ? diff.divide(openPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
            : BigDecimal.ZERO;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        map.put("label", label);
        map.put("price", ltp.setScale(2, RoundingMode.HALF_UP));
        map.put("change", diff.setScale(2, RoundingMode.HALF_UP));
        map.put("percentChange", pct.setScale(2, RoundingMode.HALF_UP));
        map.put("isUp", diff.compareTo(BigDecimal.ZERO) >= 0);
        map.put("high", highPrice.setScale(2, RoundingMode.HALF_UP));
        map.put("low", lowPrice.setScale(2, RoundingMode.HALF_UP));
        return map;
    }

    private Map<String, Object> buildStockItem(String symbol, String fallbackPrice, double pct, String sector) {
        BigDecimal ltp = marketDataService.getLtp(symbol);
        if (ltp.compareTo(BigDecimal.ZERO) == 0) {
            ltp = new BigDecimal(fallbackPrice);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        map.put("price", ltp.setScale(2, RoundingMode.HALF_UP));
        map.put("percentChange", BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP));
        map.put("sector", sector);
        return map;
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
