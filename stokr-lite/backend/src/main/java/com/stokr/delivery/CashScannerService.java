package com.stokr.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Real EOD cash-market scanners built on NSE's daily delivery/OHLC bhavcopy data
 * (com.stokr.delivery.NseDeliveryData, fetched nightly by NseDeliveryService). This is
 * genuinely directional, real-risk momentum trading, not arbitrage -- there is no
 * validated win-rate for either scanner below; any confidence figure returned is a
 * transparent formula (delivery/volume ratio, RSI), not a backtested statistic.
 */
@Service
public class CashScannerService {

    private static final Logger log = LoggerFactory.getLogger(CashScannerService.class);
    private static final int LOOKBACK_DAYS = 40; // calendar days of buffer to get ~20-27 trading days
    private static final int MIN_HISTORY_ROWS = 15;

    private final NseDeliveryDataRepository repo;

    public CashScannerService(NseDeliveryDataRepository repo) {
        this.repo = repo;
    }

    /** Single-day delivery + volume + price surge, screened for accumulation not just churn. */
    public List<Map<String, Object>> scanCashSurge() {
        Map<String, List<NseDeliveryData>> bySymbol = loadHistoryBySymbol();
        if (bySymbol.isEmpty()) return List.of();

        List<Map<String, Object>> results = new ArrayList<>();
        for (var entry : bySymbol.entrySet()) {
            List<NseDeliveryData> rows = entry.getValue();
            if (rows.size() < MIN_HISTORY_ROWS) continue;

            NseDeliveryData today = rows.get(rows.size() - 1);
            List<NseDeliveryData> priorRows = rows.subList(0, rows.size() - 1);

            Double todayDelivPct = dbl(today.getDelivPct());
            Double closeP = dbl(today.getClosePrice());
            Double prevCloseP = dbl(today.getPrevClose());
            Long todayQty = today.getTotalQty();
            if (todayDelivPct == null || closeP == null || prevCloseP == null || prevCloseP == 0 || todayQty == null) continue;

            double avgDelivPct = avg(priorRows, r -> dbl(r.getDelivPct()));
            double avgQty = avg(priorRows, r -> r.getTotalQty() != null ? r.getTotalQty().doubleValue() : null);
            if (avgDelivPct <= 0 || avgQty <= 0) continue;

            double priceChangePct = (closeP - prevCloseP) / prevCloseP * 100.0;
            double delivRatio = todayDelivPct / avgDelivPct;
            double volumeRatio = todayQty / avgQty;

            boolean qualifies = todayDelivPct >= 50.0 && delivRatio >= 1.5 && volumeRatio >= 1.5 && priceChangePct >= 2.0;
            if (!qualifies) continue;

            double atr = computeATR(rows, 14);
            if (atr <= 0) continue;

            double entryPrice = closeP;
            double stopLossPrice = round2(entryPrice - 1.5 * atr);
            double targetPrice = round2(entryPrice + 3.0 * atr);
            double score = delivRatio * volumeRatio * (1 + priceChangePct / 100.0);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", entry.getKey());
            m.put("tradeDate", today.getTradeDate().toString());
            m.put("entryPrice", round2(entryPrice));
            m.put("targetPrice", targetPrice);
            m.put("stopLossPrice", stopLossPrice);
            m.put("expectedGainPct", round2((targetPrice - entryPrice) / entryPrice * 100.0));
            m.put("priceChangePct", round2(priceChangePct));
            m.put("delivPct", round2(todayDelivPct));
            m.put("avgDelivPct20d", round2(avgDelivPct));
            m.put("deliverySurgeMultiplier", round2(delivRatio) + "x");
            m.put("volumeSurgeMultiplier", round2(volumeRatio) + "x");
            m.put("atrTrailingSL", "ATR14=" + round2(atr));
            m.put("score", round2(score));
            results.add(m);
        }

        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results;
    }

    /** Multi-day RSI momentum, screened for sustained (not one-day) delivery accumulation. */
    public List<Map<String, Object>> scanCashSwing() {
        Map<String, List<NseDeliveryData>> bySymbol = loadHistoryBySymbol();
        if (bySymbol.isEmpty()) return List.of();

        double breadthUp = 0, breadthTotal = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (var entry : bySymbol.entrySet()) {
            List<NseDeliveryData> rows = entry.getValue();
            if (rows.size() < MIN_HISTORY_ROWS) continue;

            List<Double> closes = rows.stream().map(r -> dbl(r.getClosePrice())).filter(Objects::nonNull).toList();
            if (closes.size() < 15) continue;

            // Simple 5-day breadth proxy for overall market regime (no single index series in this dataset).
            double c5 = closes.get(closes.size() - 6);
            double cNow = closes.get(closes.size() - 1);
            if (c5 > 0) {
                breadthTotal++;
                if (cNow > c5) breadthUp++;
            }

            Double rsi14 = computeRSI(closes, 14);
            if (rsi14 == null || rsi14 < 60.0 || rsi14 > 68.0) continue;

            NseDeliveryData today = rows.get(rows.size() - 1);
            Double closeP = dbl(today.getClosePrice());
            if (closeP == null) continue;

            List<NseDeliveryData> last5 = rows.subList(rows.size() - 5, rows.size());
            List<NseDeliveryData> last20 = rows.subList(Math.max(0, rows.size() - 20), rows.size());
            double avgDeliv5 = avg(last5, r -> dbl(r.getDelivPct()));
            double avgDeliv20 = avg(last20, r -> dbl(r.getDelivPct()));
            if (avgDeliv20 <= 0 || avgDeliv5 < avgDeliv20) continue; // require sustained accumulation, not a one-off

            double atr = computeATR(rows, 14);
            if (atr <= 0) continue;

            double entryPrice = closeP;
            double stopLossPrice = round2(entryPrice - 1.5 * atr);
            double targetPrice = round2(entryPrice + 2.5 * atr);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", entry.getKey());
            m.put("tradeDate", today.getTradeDate().toString());
            m.put("entryPrice", round2(entryPrice));
            m.put("targetPrice", targetPrice);
            m.put("stopLossPrice", stopLossPrice);
            m.put("rsiMomentum", round2(rsi14));
            m.put("avgDelivPct5d", round2(avgDeliv5));
            m.put("avgDelivPct20d", round2(avgDeliv20));
            m.put("holdingPeriodDays", "2-5 Days");
            m.put("score", round2(rsi14 * (avgDeliv5 / avgDeliv20)));
            results.add(m);
        }

        String breadthLabel = breadthTotal > 0
            ? round2(breadthUp / breadthTotal * 100.0) + "% of scanned universe up over 5d"
            : "n/a";
        for (Map<String, Object> m : results) m.put("niftyRegime", breadthLabel);

        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return results;
    }

    private Map<String, List<NseDeliveryData>> loadHistoryBySymbol() {
        LocalDate latest = repo.findLatestDate().orElse(null);
        if (latest == null) {
            log.debug("No NSE delivery data available yet");
            return Map.of();
        }
        LocalDate start = latest.minusDays(LOOKBACK_DAYS);
        List<NseDeliveryData> rows = repo.findRangeEQ(start, latest);
        return rows.stream().collect(Collectors.groupingBy(NseDeliveryData::getSymbol, LinkedHashMap::new, Collectors.toList()));
    }

    /** Wilder's RSI over the last `period` changes in `closes` (closes must be chronological, oldest first). */
    private Double computeRSI(List<Double> closes, int period) {
        if (closes.size() < period + 1) return null;
        List<Double> window = closes.subList(closes.size() - (period + 1), closes.size());
        double gainSum = 0, lossSum = 0;
        for (int i = 1; i < window.size(); i++) {
            double change = window.get(i) - window.get(i - 1);
            if (change > 0) gainSum += change;
            else lossSum += -change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Average True Range over the last `period` days (rows must be chronological, oldest first, today last). */
    private double computeATR(List<NseDeliveryData> rows, int period) {
        if (rows.size() < period + 1) return 0;
        List<NseDeliveryData> window = rows.subList(rows.size() - period, rows.size());
        double sum = 0;
        int count = 0;
        for (NseDeliveryData r : window) {
            Double high = dbl(r.getHighPrice());
            Double low = dbl(r.getLowPrice());
            Double prevClose = dbl(r.getPrevClose());
            if (high == null || low == null || prevClose == null) continue;
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private interface ToDouble<T> { Double apply(T t); }

    private double avg(List<NseDeliveryData> rows, ToDouble<NseDeliveryData> fn) {
        double sum = 0;
        int count = 0;
        for (NseDeliveryData r : rows) {
            Double v = fn.apply(r);
            if (v != null) { sum += v; count++; }
        }
        return count > 0 ? sum / count : 0;
    }

    private Double dbl(BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
