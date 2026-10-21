package com.stokr.arbitrage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PortfolioRiskCalculator {

    public static class PortfolioRiskStatus {
        public double globalMaxDailyLoss;
        public double globalMaxDailyProfit;
        public double todayRealizedPnl;
        public double todayUnrealizedPnl;
        public double todayNetPnl;
        public boolean halted;
        public String haltReason;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("globalMaxDailyLoss", globalMaxDailyLoss);
            m.put("globalMaxDailyProfit", globalMaxDailyProfit);
            m.put("todayRealizedPnl", Math.round(todayRealizedPnl * 100.0) / 100.0);
            m.put("todayUnrealizedPnl", Math.round(todayUnrealizedPnl * 100.0) / 100.0);
            m.put("todayNetPnl", Math.round(todayNetPnl * 100.0) / 100.0);
            m.put("halted", halted);
            m.put("haltReason", haltReason);
            return m;
        }
    }

    public static PortfolioRiskStatus evaluateDailyRisk(
            LivePositionRepository positionRepo,
            AutoExecSettingRepository settingRepo,
            Long userId,
            String broker) {

        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDateTime dayStart = LocalDate.now(ist).atStartOfDay();

        double maxLoss = 5000.0;
        double maxProfit = 10000.0;

        if (settingRepo != null) {
            String lossStr = settingRepo.findBySettingKey("globalMaxDailyLoss")
                    .map(AutoExecSetting::getSettingValue)
                    .orElse(settingRepo.findBySettingKey("maxDailyLoss")
                            .map(AutoExecSetting::getSettingValue)
                            .orElse("5000"));
            String profitStr = settingRepo.findBySettingKey("globalMaxDailyProfit")
                    .map(AutoExecSetting::getSettingValue)
                    .orElse("10000");
            try { maxLoss = Double.parseDouble(lossStr); } catch (Exception ignored) {}
            try { maxProfit = Double.parseDouble(profitStr); } catch (Exception ignored) {}
        }

        boolean isPaper = broker == null || "PAPER".equalsIgnoreCase(broker);

        double realizedPnl = 0.0;
        double unrealizedPnl = 0.0;

        if (positionRepo != null) {
            List<LivePosition> closed = positionRepo.findAllClosed();
            if (closed != null) {
                realizedPnl = closed.stream()
                        .filter(p -> p.getExitedAt() != null && !p.getExitedAt().isBefore(dayStart))
                        .filter(p -> matchesUserAndMode(p, userId, isPaper))
                        .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0.0)
                        .sum();
            }

            List<LivePosition> open = positionRepo.findAllOpen();
            if (open != null) {
                unrealizedPnl = open.stream()
                        .filter(p -> matchesUserAndMode(p, userId, isPaper))
                        .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0.0)
                        .sum();
            }
        }

        double totalNetPnl = realizedPnl + unrealizedPnl;

        PortfolioRiskStatus status = new PortfolioRiskStatus();
        status.globalMaxDailyLoss = maxLoss;
        status.globalMaxDailyProfit = maxProfit;
        status.todayRealizedPnl = realizedPnl;
        status.todayUnrealizedPnl = unrealizedPnl;
        status.todayNetPnl = totalNetPnl;

        if (maxLoss > 0 && totalNetPnl <= -maxLoss) {
            status.halted = true;
            status.haltReason = "Daily loss limit Rs." + String.format("%.0f", maxLoss)
                    + " hit (today's net P&L: Rs." + String.format("%.0f", totalNetPnl) + "). Halting all new entries.";
        } else if (maxProfit > 0 && totalNetPnl >= maxProfit) {
            status.halted = true;
            status.haltReason = "Daily profit target Rs." + String.format("%.0f", maxProfit)
                    + " reached (today's net P&L: Rs." + String.format("%.0f", totalNetPnl) + "). Halting to lock in gains.";
        } else {
            status.halted = false;
            status.haltReason = null;
        }

        return status;
    }

    private static boolean matchesUserAndMode(LivePosition p, Long userId, boolean isPaper) {
        if (p == null) return false;
        boolean posIsPaper = p.getBroker() == null || "PAPER".equalsIgnoreCase(p.getBroker());
        if (isPaper) {
            return posIsPaper;
        } else {
            if (posIsPaper) return false;
            return userId == null || userId.equals(p.getUserId());
        }
    }
}
