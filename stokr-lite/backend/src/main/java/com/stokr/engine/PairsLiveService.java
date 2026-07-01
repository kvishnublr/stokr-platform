package com.stokr.engine;

import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerOrderResponse;
import com.stokr.broker.BrokerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live execution engine for the Opening-Drift pairs strategy.
 *
 * At 9:15: pre-compute 20-day daily stats for each pair.
 * At 9:30: enter trades where opening z ∈ [1.5, 2.5].
 * Every minute: check for mean-reversion exit or stop.
 * At 15:05: force EOD close on all remaining open trades.
 *
 * Defaults to PAPER mode. Toggle via POST /api/pairs/live/mode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PairsLiveService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Capital allocated per leg (each side of the pair)
    static final double CAPITAL_PER_LEG = 25_000.0;

    // Entry: z must be in [Z_ENTRY_MIN, Z_ENTRY_MAX]
    static final double Z_ENTRY_MIN = 1.5;
    static final double Z_ENTRY_MAX = 2.5;

    // Exit: spread reverts to within Z_EXIT of mean
    static final double Z_EXIT = 0.5;

    // Stop: spread widens to Z_STOP (real event, cut loss)
    static final double Z_STOP = 4.0;

    // 20-day rolling window for daily log-ratio stats
    static final int DAILY_WINDOW = 20;

    // Brokerage per round-trip (₹80 for both legs combined)
    static final double BROKERAGE = 80.0;

    // The 5 exact-segment pairs validated in backtesting
    static final List<String[]> LIVE_PAIRS = List.of(
        new String[]{"BAJAJ-AUTO", "HEROMOTOCO"},
        new String[]{"KOTAKBANK",  "AXISBANK"},
        new String[]{"HINDALCO",   "TATASTEEL"},
        new String[]{"ASIANPAINT", "BERGEPAINT"},
        new String[]{"CIPLA",      "LUPIN"}
    );

    @Value("${pairs.mode:PAPER}")
    private String configuredMode;

    // Runtime-toggleable mode (updated via controller)
    private volatile String liveMode;

    private final CandleDataRepository candleRepository;
    private final PairTradeRepository pairTradeRepository;
    private final BrokerService brokerService;
    private final PaperBroker paperBroker;

    // Cached daily stats per pair: pairKey -> [mean, std]
    private final Map<String, double[]> dailyStats = new ConcurrentHashMap<>();

    public String getMode() {
        return liveMode != null ? liveMode : configuredMode;
    }

    public void setMode(String mode) {
        this.liveMode = mode.toUpperCase();
        log.info("[PAIRS] Mode switched to {}", this.liveMode);
    }

    // ─── 9:15 AM — pre-compute 20-day stats ─────────────────────────────────

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void computeDailyStats() {
        log.info("[PAIRS] 9:15 — computing daily stats for {} pairs", LIVE_PAIRS.size());
        dailyStats.clear();
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime histStart = now.minusDays(40).toLocalDate().atStartOfDay();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();

        for (String[] pair : LIVE_PAIRS) {
            String symA = pair[0], symB = pair[1];
            try {
                double[] stats = buildDailyStats(symA, symB, histStart, todayStart);
                if (stats == null) {
                    log.warn("[PAIRS] Insufficient history for {}/{}", symA, symB);
                    continue;
                }
                dailyStats.put(symA + ":" + symB, stats);

                // Preview expected z at open using latest 1-min close
                List<CandleData> recentA = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symA, "1min", now.minusMinutes(20), now);
                List<CandleData> recentB = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symB, "1min", now.minusMinutes(20), now);
                if (!recentA.isEmpty() && !recentB.isEmpty()) {
                    double pA = recentA.get(recentA.size() - 1).getClose().doubleValue();
                    double pB = recentB.get(recentB.size() - 1).getClose().doubleValue();
                    double z  = (Math.log(pA / pB) - stats[0]) / stats[1];
                    if (Math.abs(z) >= Z_ENTRY_MIN && Math.abs(z) <= Z_ENTRY_MAX) {
                        String dir = z > 0 ? "SHORT_A_LONG_B" : "LONG_A_SHORT_B";
                        log.info("[PAIRS] 🎯 Pre-signal {}/{} z={} → {} entry at 9:30 [{}]",
                            symA, symB, String.format("%.2f", z), dir, getMode());
                    } else {
                        log.debug("[PAIRS] {}/{} z={} — no trade today", symA, symB, String.format("%.2f", z));
                    }
                }
            } catch (Exception e) {
                log.error("[PAIRS] Stats error for {}/{}", symA, symB, e);
            }
        }
    }

    // ─── 9:30 AM — enter pairs ────────────────────────────────────────────────

    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void enterPairsAtOpen() {
        LocalDate today = LocalDate.now(IST);
        long alreadyEntered = pairTradeRepository.countByEntryTimeBetween(
            today.atStartOfDay(), today.atTime(9, 35));
        if (alreadyEntered > 0) {
            log.info("[PAIRS] {} trades already entered today — skipping duplicate entry", alreadyEntered);
            return;
        }

        for (String[] pair : LIVE_PAIRS) {
            String symA = pair[0], symB = pair[1];
            String pairKey = symA + ":" + symB;
            try {
                double[] stats = ensureStats(symA, symB, pairKey);
                if (stats == null) { log.warn("[PAIRS] No stats for {} — skip", pairKey); continue; }

                LocalDateTime now = LocalDateTime.now(IST);
                List<CandleData> recentA = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symA, "1min", now.minusMinutes(5), now);
                List<CandleData> recentB = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symB, "1min", now.minusMinutes(5), now);
                if (recentA.isEmpty() || recentB.isEmpty()) {
                    log.warn("[PAIRS] No candles for {} at 9:30", pairKey);
                    continue;
                }

                double priceA = recentA.get(recentA.size() - 1).getClose().doubleValue();
                double priceB = recentB.get(recentB.size() - 1).getClose().doubleValue();
                double openZ  = (Math.log(priceA / priceB) - stats[0]) / stats[1];

                if (Math.abs(openZ) < Z_ENTRY_MIN) {
                    log.debug("[PAIRS] {}/{} z={} below entry threshold", symA, symB, String.format("%.2f", openZ));
                    continue;
                }
                if (Math.abs(openZ) > Z_ENTRY_MAX) {
                    log.info("[PAIRS] {}/{} z={} above max — likely real event, skipping", symA, symB, String.format("%.2f", openZ));
                    continue;
                }

                String direction = openZ > 0 ? "SHORT_A_LONG_B" : "LONG_A_SHORT_B";
                int qtyA = Math.max(1, (int)(CAPITAL_PER_LEG / priceA));
                int qtyB = Math.max(1, (int)(CAPITAL_PER_LEG / priceB));

                String sideA = direction.equals("SHORT_A_LONG_B") ? "SELL" : "BUY";
                String sideB = direction.equals("SHORT_A_LONG_B") ? "BUY"  : "SELL";

                String orderIdA = executeOrder(symA, sideA, qtyA, priceA);
                String orderIdB = executeOrder(symB, sideB, qtyB, priceB);

                PairTrade trade = PairTrade.builder()
                    .pairKey(pairKey).symbolA(symA).symbolB(symB)
                    .direction(direction).entryTime(now)
                    .dailyMean(stats[0]).dailyStd(stats[1])
                    .entryZ(openZ).entryPriceA(priceA).entryPriceB(priceB)
                    .qtyA(qtyA).qtyB(qtyB)
                    .status("OPEN").mode(getMode())
                    .orderIdA(orderIdA).orderIdB(orderIdB)
                    .build();
                pairTradeRepository.save(trade);

                log.info("[PAIRS] ✅ ENTERED {} {} z={} A={} x{} B={} x{} [{}]",
                    pairKey, direction, String.format("%.2f", openZ),
                    priceA, qtyA, priceB, qtyB, getMode());

            } catch (Exception e) {
                log.error("[PAIRS] Entry error for {}", pairKey, e);
            }
        }
    }

    // ─── Every minute — monitor open trades ──────────────────────────────────

    @Scheduled(cron = "30 31-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "30 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void monitorOpenTrades() {
        List<PairTrade> open = pairTradeRepository.findByStatus("OPEN");
        if (open.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(IST);
        for (PairTrade trade : open) {
            try {
                checkExit(trade, now, false);
            } catch (Exception e) {
                log.error("[PAIRS] Monitor error for {}", trade.getPairKey(), e);
            }
        }
    }

    // ─── 15:05 — force EOD close ─────────────────────────────────────────────

    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceEodClose() {
        List<PairTrade> open = pairTradeRepository.findByStatus("OPEN");
        if (open.isEmpty()) { log.info("[PAIRS] EOD: no open trades"); return; }
        log.info("[PAIRS] EOD force-closing {} open trade(s)", open.size());
        LocalDateTime now = LocalDateTime.now(IST);
        for (PairTrade trade : open) {
            try {
                checkExit(trade, now, true);
            } catch (Exception e) {
                log.error("[PAIRS] EOD close error for {}", trade.getPairKey(), e);
            }
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void checkExit(PairTrade trade, LocalDateTime now, boolean forceClose) {
        List<CandleData> recentA = candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                trade.getSymbolA(), "1min", now.minusMinutes(3), now);
        List<CandleData> recentB = candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                trade.getSymbolB(), "1min", now.minusMinutes(3), now);
        if (recentA.isEmpty() || recentB.isEmpty()) return;

        double curA = recentA.get(recentA.size() - 1).getClose().doubleValue();
        double curB = recentB.get(recentB.size() - 1).getClose().doubleValue();
        double curZ = (Math.log(curA / curB) - trade.getDailyMean()) / trade.getDailyStd();

        boolean hitTarget = "SHORT_A_LONG_B".equals(trade.getDirection()) ? curZ <= Z_EXIT : curZ >= -Z_EXIT;
        boolean hitStop   = Math.abs(curZ) >= Z_STOP;

        if (!forceClose && !hitTarget && !hitStop) return;

        String reason = forceClose ? "EOD_EXIT" : (hitStop ? "SPREAD_STOP" : "SPREAD_REVERSION");

        double legAPnl, legBPnl;
        if ("SHORT_A_LONG_B".equals(trade.getDirection())) {
            legAPnl = (trade.getEntryPriceA() - curA) / trade.getEntryPriceA() * CAPITAL_PER_LEG;
            legBPnl = (curB - trade.getEntryPriceB()) / trade.getEntryPriceB() * CAPITAL_PER_LEG;
        } else {
            legAPnl = (curA - trade.getEntryPriceA()) / trade.getEntryPriceA() * CAPITAL_PER_LEG;
            legBPnl = (trade.getEntryPriceB() - curB) / trade.getEntryPriceB() * CAPITAL_PER_LEG;
        }
        double netPnl = legAPnl + legBPnl - BROKERAGE;

        // Reverse orders to close
        String exitSideA = "SHORT_A_LONG_B".equals(trade.getDirection()) ? "BUY" : "SELL";
        String exitSideB = "SHORT_A_LONG_B".equals(trade.getDirection()) ? "SELL" : "BUY";
        executeOrder(trade.getSymbolA(), exitSideA, trade.getQtyA(), curA);
        executeOrder(trade.getSymbolB(), exitSideB, trade.getQtyB(), curB);

        trade.setExitTime(now);
        trade.setExitPriceA(curA);
        trade.setExitPriceB(curB);
        trade.setExitZ(curZ);
        trade.setLegAPnl(legAPnl);
        trade.setLegBPnl(legBPnl);
        trade.setNetPnl(netPnl);
        trade.setExitReason(reason);
        trade.setStatus("CLOSED");
        pairTradeRepository.save(trade);

        log.info("[PAIRS] {} {}/{} exitZ={} legA={} legB={} net={} [{}]",
            reason, trade.getSymbolA(), trade.getSymbolB(),
            String.format("%.2f", curZ),
            String.format("%.0f", legAPnl),
            String.format("%.0f", legBPnl),
            String.format("%.0f", netPnl),
            trade.getMode());
    }

    private String executeOrder(String symbol, String side, int qty, double price) {
        String mode = getMode();
        try {
            BrokerOrderRequest req = BrokerOrderRequest.builder()
                .symbol(symbol).exchange("NSE")
                .side(BrokerOrderRequest.Side.valueOf(side))
                .quantity(qty).price(null)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .productType("MIS")
                .build();

            if ("LIVE".equals(mode)) {
                var accounts = brokerService.getActiveAccountsByBroker("ZERODHA");
                if (accounts.isEmpty()) {
                    log.error("[PAIRS][LIVE] No active Zerodha account — cannot place order");
                    return "NO_ACCOUNT";
                }
                String accessToken = accounts.get(0).getAccessToken();
                BrokerOrderResponse resp = brokerService.getAdapter("ZERODHA").placeOrder(accessToken, req);
                log.info("[PAIRS][LIVE] {} {} qty={} @{} → {}", side, symbol, qty, String.format("%.2f", price), resp.orderId());
                return resp.orderId();
            } else {
                BrokerOrderResponse resp = paperBroker.placeOrder("paper", req);
                return resp.orderId();
            }
        } catch (Exception e) {
            log.error("[PAIRS] Order failed {} {} qty={}", side, symbol, qty, e);
            return "FAILED-" + System.currentTimeMillis();
        }
    }

    /**
     * Return cached stats or recompute on the fly (for 9:30 if 9:15 missed due to restart).
     */
    private double[] ensureStats(String symA, String symB, String pairKey) {
        double[] cached = dailyStats.get(pairKey);
        if (cached != null) return cached;
        LocalDateTime now = LocalDateTime.now(IST);
        double[] stats = buildDailyStats(symA, symB,
            now.minusDays(40).toLocalDate().atStartOfDay(),
            now.toLocalDate().atStartOfDay());
        if (stats != null) dailyStats.put(pairKey, stats);
        return stats;
    }

    /**
     * Compute 20-day rolling mean/std of daily log(closeA/closeB) for dates in [histStart, histEnd).
     * Uses the last candle of each trading day as the daily close.
     */
    double[] buildDailyStats(String symA, String symB, LocalDateTime histStart, LocalDateTime histEnd) {
        List<CandleData> cA = candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symA, "1min", histStart, histEnd);
        List<CandleData> cB = candleRepository
            .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symB, "1min", histStart, histEnd);
        if (cA.isEmpty() || cB.isEmpty()) return null;

        // Map timestampB -> closeB for fast lookup
        Map<LocalDateTime, Double> mapB = new HashMap<>(cB.size());
        for (CandleData c : cB) mapB.put(c.getTimestamp(), c.getClose().doubleValue());

        // Extract last matching candle per day (daily close)
        TreeMap<LocalDate, double[]> byDate = new TreeMap<>();
        for (CandleData ca : cA) {
            Double cb = mapB.get(ca.getTimestamp());
            if (cb == null || cb <= 0) continue;
            double closeA = ca.getClose().doubleValue();
            if (closeA <= 0) continue;
            byDate.put(ca.getTimestamp().toLocalDate(), new double[]{closeA, cb});
        }

        List<double[]> days = new ArrayList<>(byDate.values());
        if (days.size() < DAILY_WINDOW) return null;

        // Use the last DAILY_WINDOW days as the rolling window
        List<double[]> window = days.subList(days.size() - DAILY_WINDOW, days.size());
        double sum = 0, sumSq = 0;
        for (double[] d : window) {
            double r = Math.log(d[0] / d[1]);
            sum   += r;
            sumSq += r * r;
        }
        double mean = sum / DAILY_WINDOW;
        double std  = Math.sqrt(sumSq / DAILY_WINDOW - mean * mean);
        if (std < 1e-8) return null;
        return new double[]{mean, std};
    }
}
