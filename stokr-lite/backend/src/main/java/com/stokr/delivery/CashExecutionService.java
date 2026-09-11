package com.stokr.delivery;

import com.stokr.arbitrage.ZerodhaSpotPriceFetcher;
import com.stokr.broker.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashExecutionService {

    private static final int MAX_OPEN_PAPER = 10;
    private static final double PAPER_CAPITAL = 10000.0;
    private static final int MAX_HOLD_DAYS_SURGE = 3;
    private static final int MAX_HOLD_DAYS_SWING = 5;
    private static final int SYMBOL_COOLDOWN_HOURS = 24;

    private final CashPositionRepository positionRepo;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    @org.springframework.context.annotation.Lazy @org.springframework.beans.factory.annotation.Autowired private CashScannerService cashScannerService;

    public Map<String, Object> execute(String symbol, String strategyType, double targetPrice, double stopLossPrice, String broker, double capital) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (symbol == null || symbol.isBlank()) {
            result.put("status", "ERROR");
            result.put("message", "Missing symbol");
            return result;
        }

        double ltp;
        try {
            ltp = spotFetcher.getSpotPrice("NSE:" + symbol);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Could not fetch live price for " + symbol + ": " + e.getMessage());
            return result;
        }
        if (ltp <= 0) {
            result.put("status", "ERROR");
            result.put("message", "No live price available for " + symbol + " (market closed or illiquid)");
            return result;
        }

        int qty = (int) Math.floor(capital / ltp);
        if (qty < 1) qty = 1;

        CashPosition pos = CashPosition.builder()
                .symbol(symbol)
                .strategyType(strategyType)
                .side("BUY")
                .quantity(qty)
                .entryPrice(BigDecimal.valueOf(ltp))
                .targetPrice(targetPrice > 0 ? BigDecimal.valueOf(targetPrice) : null)
                .stopLossPrice(stopLossPrice > 0 ? BigDecimal.valueOf(stopLossPrice) : null)
                .broker(broker)
                .status("OPEN")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        if (broker == null || broker.isBlank() || "PAPER".equalsIgnoreCase(broker)) {
            pos.setOrderId("PAPER-" + System.currentTimeMillis());
            positionRepo.save(pos);
            result.put("status", "SUCCESS");
            result.put("symbol", symbol);
            result.put("quantity", qty);
            result.put("entryPrice", ltp);
            result.put("message", "BUY " + qty + " " + symbol + " @ Rs" + ltp + " entered as PAPER trade");
            return result;
        }

        Long userId;
        BrokerAccount account;
        BrokerAdapter adapter;
        try {
            userId = brokerAccountRepo.findByStatus("ACTIVE").stream()
                    .findFirst().map(BrokerAccount::getUserId).orElse(null);
            if (userId == null) { result.put("status", "ERROR"); result.put("message", "No active broker account"); return result; }
            List<BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker, "ACTIVE");
            if (accounts.isEmpty()) { result.put("status", "ERROR"); result.put("message", "No " + broker + " account found"); return result; }
            account = accounts.get(0);
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Broker setup failed: " + e.getMessage());
            return result;
        }

        try {
            BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            double available = margin != null ? margin.doubleValue() : 0;
            double needed = ltp * qty;
            if (needed > available * 0.9) {
                result.put("status", "ERROR");
                result.put("message", "Needs Rs" + Math.round(needed) + " but only Rs" + Math.round(available) + " available");
                return result;
            }
        } catch (Exception e) {
            log.warn("Cash execution: margin check failed, proceeding without pre-check: {}", e.getMessage());
        }

        try {
            BrokerOrderRequest req = BrokerOrderRequest.builder()
                    .symbol(symbol).exchange("NSE")
                    .side(BrokerOrderRequest.Side.BUY)
                    .quantity(qty).price(0.0)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("CNC")
                    .build();
            BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);
            if (!resp.isSuccess() || resp.orderId() == null || resp.orderId().isBlank()) {
                pos.setStatus("FAILED");
                pos.setErrorMessage(resp.message());
                positionRepo.save(pos);
                result.put("status", "ERROR");
                result.put("message", "Order failed: " + resp.message());
                return result;
            }
            pos.setOrderId(resp.orderId());
            positionRepo.save(pos);
            result.put("status", "SUCCESS");
            result.put("symbol", symbol);
            result.put("quantity", qty);
            result.put("entryPrice", ltp);
            result.put("message", "BUY " + qty + " " + symbol + " @ Rs" + ltp + " entered LIVE via " + broker);
            return result;
        } catch (Exception e) {
            pos.setStatus("FAILED");
            pos.setErrorMessage(e.getMessage());
            positionRepo.save(pos);
            result.put("status", "ERROR");
            result.put("message", "Execution failed: " + e.getMessage());
            return result;
        }
    }

    public List<Map<String, Object>> getClosedPositions() {
        return positionRepo.findRecentClosed().stream().map(CashPosition::toMap).toList();
    }

    public List<Map<String, Object>> getOpenPositionsWithLivePnl() {
        List<CashPosition> open = positionRepo.findAllOpen();
        return open.stream().map(p -> {
            Map<String, Object> m = p.toMap();
            try {
                double ltp = spotFetcher.getSpotPrice("NSE:" + p.getSymbol());
                if (ltp > 0 && p.getEntryPrice() != null && p.getQuantity() != null) {
                    double pnl = (ltp - p.getEntryPrice().doubleValue()) * p.getQuantity();
                    double pnlPct = (ltp - p.getEntryPrice().doubleValue()) / p.getEntryPrice().doubleValue() * 100.0;
                    m.put("currentPrice", ltp);
                    m.put("currentPnl", Math.round(pnl));
                    m.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
                    long holdDays = p.getEnteredAt() != null ? ChronoUnit.DAYS.between(p.getEnteredAt().toLocalDate(), LocalDateTime.now().toLocalDate()) : 0;
                    m.put("holdDays", holdDays);
                }
            } catch (Exception ignored) {}
            return m;
        }).toList();
    }

    /** Square off on target hit, stop-loss breach, or max holding period exceeded. Checked every 30s. */
    @Scheduled(fixedDelayString = "30000", initialDelay = 30000)
    public void checkExits() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        List<CashPosition> open = positionRepo.findAllOpen();
        if (open.isEmpty()) return;

        for (CashPosition pos : open) {
            double ltp;
            try {
                ltp = spotFetcher.getSpotPrice("NSE:" + pos.getSymbol());
            } catch (Exception e) {
                continue;
            }
            if (ltp <= 0) continue;

            double target = pos.getTargetPrice() != null ? pos.getTargetPrice().doubleValue() : 0;
            double stopLoss = pos.getStopLossPrice() != null ? pos.getStopLossPrice().doubleValue() : 0;
            boolean hitTarget = target > 0 && ltp >= target;
            boolean hitStop = stopLoss > 0 && ltp <= stopLoss;

            int maxHoldDays = "CASH_SURGE".equals(pos.getStrategyType()) ? MAX_HOLD_DAYS_SURGE : MAX_HOLD_DAYS_SWING;
            long holdDays = pos.getEnteredAt() != null ? ChronoUnit.DAYS.between(pos.getEnteredAt().toLocalDate(), LocalDateTime.now().toLocalDate()) : 0;
            boolean holdExpired = holdDays >= maxHoldDays;

            if (!hitTarget && !hitStop && !holdExpired) continue;

            String exitReason = hitTarget ? "TARGET_HIT" : hitStop ? "STOP_LOSS" : "MAX_HOLD_" + maxHoldDays + "D";

            boolean isPaper = pos.getOrderId() != null && pos.getOrderId().startsWith("PAPER");
            boolean squaredOff = true;
            if (!isPaper) {
                try {
                    Long userId = brokerAccountRepo.findByStatus("ACTIVE").stream()
                            .findFirst().map(BrokerAccount::getUserId).orElse(null);
                    if (userId != null) {
                        List<BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, pos.getBroker(), "ACTIVE");
                        if (!accounts.isEmpty()) {
                            BrokerAccount account = accounts.get(0);
                            BrokerAdapter adapter = brokerService.getAdapter(pos.getBroker());
                            BrokerOrderRequest req = BrokerOrderRequest.builder()
                                    .symbol(pos.getSymbol()).exchange("NSE")
                                    .side(BrokerOrderRequest.Side.SELL)
                                    .quantity(pos.getQuantity()).price(0.0)
                                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                                    .productType("CNC")
                                    .build();
                            BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);
                            squaredOff = resp.isSuccess();
                        } else {
                            squaredOff = false;
                        }
                    } else {
                        squaredOff = false;
                    }
                } catch (Exception e) {
                    log.error("Cash position square-off failed for {}: {}", pos.getSymbol(), e.getMessage());
                    squaredOff = false;
                }
            }
            if (!squaredOff) continue;

            double pnl = (ltp - pos.getEntryPrice().doubleValue()) * pos.getQuantity();
            pos.setStatus("CLOSED");
            pos.setExitedAt(LocalDateTime.now());
            pos.setExitPrice(BigDecimal.valueOf(ltp));
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            pos.setErrorMessage(exitReason);
            positionRepo.save(pos);
            log.info("{}: {} {} squared off @ {} -- P&L Rs{} (held {}d)", exitReason,
                    pos.getSymbol(), pos.getQuantity(), ltp, Math.round(pnl), holdDays);
        }
    }

    /** Auto execute Paper trades for Cash Surge and Cash Swing - max 10 trades, 10k each */
    @Scheduled(fixedDelayString = "60000", initialDelay = 60000)
    public void autoPaperTradeCashSignals() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        List<CashPosition> allPositions = positionRepo.findAllByOrderByEnteredAtDesc();
        List<CashPosition> openPositions = allPositions.stream().filter(p -> "OPEN".equals(p.getStatus())).toList();
        long openPaperCount = openPositions.stream().filter(p -> "PAPER".equals(p.getBroker())).count();
        if (openPaperCount >= MAX_OPEN_PAPER) return;

        java.util.Set<String> openPaperSymbols = openPositions.stream()
                .filter(p -> "PAPER".equals(p.getBroker()))
                .map(CashPosition::getSymbol)
                .collect(java.util.stream.Collectors.toSet());

        LocalDateTime cooldownCutoff = LocalDateTime.now().minusHours(SYMBOL_COOLDOWN_HOURS);
        java.util.Set<String> recentlyClosedSymbols = allPositions.stream()
                .filter(p -> "CLOSED".equals(p.getStatus()) && "PAPER".equals(p.getBroker()))
                .filter(p -> p.getExitedAt() != null && p.getExitedAt().isAfter(cooldownCutoff))
                .map(CashPosition::getSymbol)
                .collect(java.util.stream.Collectors.toSet());

        try {
            List<Map<String, Object>> surgeOpps = cashScannerService.scanCashSurge();
            List<Map<String, Object>> swingOpps = cashScannerService.scanCashSwing();

            for (Map<String, Object> opp : surgeOpps) {
                if (openPaperCount >= MAX_OPEN_PAPER) break;
                String symbol = (String) opp.get("symbol");
                if (openPaperSymbols.contains(symbol) || recentlyClosedSymbols.contains(symbol)) continue;
                double target = opp.get("targetPrice") instanceof Number n ? n.doubleValue() : 0;
                double sl = opp.get("stopLossPrice") instanceof Number n ? n.doubleValue() : 0;
                execute(symbol, "CASH_SURGE", target, sl, "PAPER", PAPER_CAPITAL);
                openPaperCount++;
                openPaperSymbols.add(symbol);
            }

            for (Map<String, Object> opp : swingOpps) {
                if (openPaperCount >= MAX_OPEN_PAPER) break;
                String symbol = (String) opp.get("symbol");
                if (openPaperSymbols.contains(symbol) || recentlyClosedSymbols.contains(symbol)) continue;
                double target = opp.get("targetPrice") instanceof Number n ? n.doubleValue() : 0;
                double sl = opp.get("stopLossPrice") instanceof Number n ? n.doubleValue() : 0;
                execute(symbol, "CASH_SWING", target, sl, "PAPER", PAPER_CAPITAL);
                openPaperCount++;
                openPaperSymbols.add(symbol);
            }

        } catch (Exception e) {
            log.error("Auto paper trade failed: {}", e.getMessage());
        }
    }
}
