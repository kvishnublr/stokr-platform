package com.stokr.engine;

import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Manages the paper trading ecosystem and strategy marketplace.
 * <p>
 * Core features:
 * - Paper trading with virtual ₹20K balance per user
 * - Strategy performance dashboard (live stats per strategy)
 * - One-click paper deploy
 * - Profit share tracking (25% admin commission)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final VirtualWalletRepository walletRepo;
    private final StrategyRepository strategyRepo;
    private final SignalRepository signalRepo;
    private final DeploymentRepository deploymentRepo;

    // ──── Wallet Management ────

    @Transactional
    public VirtualWallet getOrCreateWallet(Long userId) {
        return walletRepo.findByUserIdAndActiveTrue(userId)
            .orElseGet(() -> {
                VirtualWallet w = VirtualWallet.builder()
                    .userId(userId)
                    .initialBalance(new BigDecimal("20000"))
                    .currentBalance(new BigDecimal("20000"))
                    .active(true)
                    .build();
                return walletRepo.save(w);
            });
    }

    @Transactional
    public VirtualWallet updatePaperPnl(Long userId, BigDecimal pnl, boolean isWin) {
        VirtualWallet w = getOrCreateWallet(userId);
        w.setCurrentBalance(w.getCurrentBalance().add(pnl));
        w.setTotalPnl(w.getTotalPnl().add(pnl));
        w.setTotalTrades(w.getTotalTrades() + 1);
        if (isWin) w.setWinningTrades(w.getWinningTrades() + 1);
        return walletRepo.save(w);
    }

    public Map<String, Object> getWalletStats(Long userId) {
        VirtualWallet w = getOrCreateWallet(userId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("initialBalance", w.getInitialBalance());
        stats.put("currentBalance", w.getCurrentBalance());
        stats.put("totalPnl", w.getTotalPnl());
        stats.put("totalTrades", w.getTotalTrades());
        stats.put("winningTrades", w.getWinningTrades());
        stats.put("winRate", w.getTotalTrades() > 0
            ? r2((double) w.getWinningTrades() / w.getTotalTrades() * 100) + "%" : "0%");

        // Monthly projection
        BigDecimal monthlyPnl = w.getCurrentBalance().subtract(w.getInitialBalance());
        stats.put("monthlyPnl", monthlyPnl);
        stats.put("monthlyRoi", w.getInitialBalance().compareTo(BigDecimal.ZERO) > 0
            ? r2(monthlyPnl.divide(w.getInitialBalance(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue()) + "%" : "0%");

        // Projected admin commission (25%)
        BigDecimal adminCut = monthlyPnl.compareTo(BigDecimal.ZERO) > 0
            ? monthlyPnl.multiply(new BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        stats.put("projectedAdminCommission", adminCut);

        return stats;
    }

    // ──── Strategy Marketplace ────

    /**
     * Get all enabled strategies with live performance metrics.
     * This is the marketplace listing users see.
     */
    public List<Map<String, Object>> getMarketplaceStrategies() {
        List<Strategy> strategies = strategyRepo.findByEnabledTrue();
        List<Map<String, Object>> marketplace = new ArrayList<>();

        for (Strategy s : strategies) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", s.getId());
            card.put("name", s.getName());
            card.put("type", s.getStrategyType());
            card.put("description", s.getDescription());
            card.put("assetClass", s.getAssetClass());

            // Performance stats from signal history
            Map<String, Object> perf = getStrategyPerformance(s.getId());
            card.put("performance", perf);

            // Risk tier
            String riskTier = determineRiskTier(s.getStrategyType());
            card.put("riskTier", riskTier);

            // Recommended capital allocation
            card.put("recommendedCapital", getRecommendedCapital(s.getStrategyType()));

            marketplace.add(card);
        }
        return marketplace;
    }

    /**
     * Compute live performance stats for a strategy from its signal history.
     */
    public Map<String, Object> getStrategyPerformance(Long strategyId) {
        List<SignalEntity> signals = signalRepo.findByStrategyIdOrderByCreatedAtDesc(strategyId);
        Map<String, Object> perf = new LinkedHashMap<>();

        if (signals.isEmpty()) {
            perf.put("totalSignals", 0);
            perf.put("note", "No historical data yet — backtest available");
            return perf;
        }

        // Count EXECUTED signals first
        List<SignalEntity> executed = signals.stream()
            .filter(s -> "EXECUTED".equals(s.getStatus()) ||
                        (s.getExitType() != null && !s.getExitType().isBlank()))
            .toList();

        long wins = executed.stream()
            .filter(s -> "TARGET_HIT".equals(s.getExitType()) || "TRAIL_HIT".equals(s.getExitType()))
            .count();
        long losses = executed.stream()
            .filter(s -> "SL_HIT".equals(s.getExitType()) || "BTST_GAP_EXIT".equals(s.getExitType()))
            .count();

        perf.put("totalSignals", signals.size());
        perf.put("executedTrades", executed.size());
        perf.put("wins", wins);
        perf.put("losses", losses);
        perf.put("winRate", executed.isEmpty() ? "N/A"
            : r2((double) wins / executed.size() * 100) + "%");

        // P&L from completed trades
        double totalPnl = executed.stream()
            .filter(s -> s.getEntryPrice() != null && s.getTarget() != null && s.getStopLoss() != null)
            .mapToDouble(s -> {
                if ("TARGET_HIT".equals(s.getExitType())) {
                    return s.getTarget().subtract(s.getEntryPrice()).doubleValue();
                } else if ("SL_HIT".equals(s.getExitType()) || "BTST_GAP_EXIT".equals(s.getExitType())) {
                    return s.getStopLoss().subtract(s.getEntryPrice()).doubleValue();
                }
                return 0;
            }).sum();
        perf.put("totalPnl", r2(totalPnl));

        // Recent trades (last 10)
        List<Map<String, Object>> recent = signals.stream()
            .filter(s -> s.getExitType() != null)
            .limit(10)
            .map(s -> Map.of(
                "symbol", (Object) s.getSymbol(),
                "entryPrice", s.getEntryPrice(),
                "exitType", s.getExitType(),
                "createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null
            ))
            .toList();
        perf.put("recentTrades", recent);

        return perf;
    }

    // ──── Profit Share ────

    /**
     * Calculate admin commission (25% of realized profits).
     * Only on POSITIVE P&L. No commission on losses.
     */
    public Map<String, Object> calculateProfitShare(Long userId, LocalDate from, LocalDate to) {
        List<SignalEntity> signals = signalRepo
            .findByUserIdAndExitTypeIsNotNullAndExitTimeBetween(userId,
                from.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
                to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());

        double totalGrossPnl = 0;
        double totalAdminCut = 0;
        int profitableTrades = 0;

        for (SignalEntity s : signals) {
            if (s.getEntryPrice() == null) continue;
            double tradePnl = 0;

            if ("TARGET_HIT".equals(s.getExitType()) && s.getTarget() != null) {
                tradePnl = s.getTarget().subtract(s.getEntryPrice()).doubleValue();
            } else if ("TRAIL_HIT".equals(s.getExitType()) && s.getStopLoss() != null) {
                tradePnl = s.getStopLoss().subtract(s.getEntryPrice()).doubleValue();
                if (tradePnl <= 0) tradePnl = 0; // trail below entry = no profit
            } else if ("SL_HIT".equals(s.getExitType()) || "BTST_GAP_EXIT".equals(s.getExitType())) {
                tradePnl = s.getStopLoss().subtract(s.getEntryPrice()).doubleValue();
            } else if ("EOD_EXIT".equals(s.getExitType())) {
                // Use entry vs exit time to approximate
                tradePnl = 0;
            }

            totalGrossPnl += tradePnl;
            if (tradePnl > 0) {
                totalAdminCut += tradePnl * 0.25; // 25% commission
                profitableTrades++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", from + " to " + to);
        result.put("totalTrades", signals.size());
        result.put("profitableTrades", profitableTrades);
        result.put("totalGrossPnl", r2(totalGrossPnl));
        result.put("adminCommission", r2(totalAdminCut));
        result.put("commissionRate", "25%");
        result.put("userKeeps", r2(totalGrossPnl - totalAdminCut));

        return result;
    }

    // ──── One-Click Paper Deploy ────

    @Transactional
    public Deployment createPaperDeployment(Long userId, Long strategyId, BigDecimal capital) {
        Strategy strategy = strategyRepo.findById(strategyId)
            .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + strategyId));

        Deployment deployment = Deployment.builder()
            .userId(userId)
            .strategyId(strategyId)
            .mode("PAPER")
            .capital(new BigDecimal(String.valueOf(capital)))
            .status("ACTIVE")
            .build();

        return deploymentRepo.save(deployment);
    }

    // ──── Helpers ────

    private String determineRiskTier(String strategyType) {
        return switch (strategyType) {
            case "BTST" -> "LOW";
            case "TWENTY_DAY_BREAKOUT" -> "MEDIUM";
            case "THREE_DAY_MOMENTUM" -> "MEDIUM";
            case "PAIRS_TRADING" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private BigDecimal getRecommendedCapital(String strategyType) {
        return switch (strategyType) {
            case "BTST" -> new BigDecimal("30000");
            case "TWENTY_DAY_BREAKOUT" -> new BigDecimal("35000");
            case "THREE_DAY_MOMENTUM" -> new BigDecimal("35000");
            case "PAIRS_TRADING" -> new BigDecimal("50000");
            default -> new BigDecimal("25000");
        };
    }

    private static String r2(double v) {
        return String.format("%.2f", Math.round(v * 100.0) / 100.0);
    }
}
