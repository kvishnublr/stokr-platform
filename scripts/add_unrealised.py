#!/usr/bin/env python3
"""Add maxUnrealizedLoss, maxUnrealizedProfit to SimulatedTrade and track them in simulation loop."""

FILE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

with open(FILE, "r") as f:
    content = f.read()

# 1. Add fields to SimulatedTrade class
old_fields = """        double pnl, brokerage, perTradeCost;
        int qty;
        double tradeCapital;
        BigDecimal exitPriceFinal;
        String reason;"""

new_fields = """        double pnl, brokerage, perTradeCost;
        int qty;
        double tradeCapital;
        BigDecimal exitPriceFinal;
        String reason;
        double maxUnrealizedLoss = 0.0;   // worst adverse excursion (rupees, negative)
        double maxUnrealizedProfit = 0.0;  // best favorable excursion (rupees, positive)"""

content = content.replace(old_fields, new_fields)

# 2. Add to toMap()
old_tomap = """            m.put("netPnl", Math.round((pnl - brokerage) * 100.0) / 100.0);
            return m;"""

new_tomap = """            m.put("netPnl", Math.round((pnl - brokerage) * 100.0) / 100.0);
            m.put("maxUnrealizedLoss", Math.round(maxUnrealizedLoss * 100.0) / 100.0);
            m.put("maxUnrealizedProfit", Math.round(maxUnrealizedProfit * 100.0) / 100.0);
            return m;"""

content = content.replace(old_tomap, new_tomap)

# 3. Add tracking in the simulation loop — BUY side
# After bestPrice tracking, add unrealised P&L tracking
old_buy_best = """                            if (c.low().compareTo(currentSL) <= 0) {
                                String exitLabel = targetLocked ? "TARGET_HIT" : (trailActivated ? "TRAIL_SL" : "SL_HIT");
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
                        } else {"""

new_buy_best = """                            // Track unrealised P&L extremes
                            double unrealHighPnl = (c.high().doubleValue() - entryD) / entryD * tradeCapital;
                            double unrealLowPnl  = (c.low().doubleValue() - entryD) / entryD * tradeCapital;
                            if (unrealHighPnl > trade.maxUnrealizedProfit) trade.maxUnrealizedProfit = unrealHighPnl;
                            if (unrealLowPnl < trade.maxUnrealizedLoss) trade.maxUnrealizedLoss = unrealLowPnl;

                            if (c.low().compareTo(currentSL) <= 0) {
                                String exitLabel = targetLocked ? "TARGET_HIT" : (trailActivated ? "TRAIL_SL" : "SL_HIT");
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
                        } else {"""

content = content.replace(old_buy_best, new_buy_best)

# 4. Add tracking for SELL side
old_sell_best = """                            if (c.high().compareTo(currentSL) >= 0) {
                                String exitLabel = targetLocked ? "TARGET_HIT" : (trailActivated ? "TRAIL_SL" : "SL_HIT");
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
                        }"""

new_sell_best = """                            // Track unrealised P&L extremes (SHORT: price drop = profit)
                            double unrealHighPnl = (entryD - c.high().doubleValue()) / entryD * tradeCapital;
                            double unrealLowPnl  = (entryD - c.low().doubleValue()) / entryD * tradeCapital;
                            if (unrealHighPnl > trade.maxUnrealizedProfit) trade.maxUnrealizedProfit = unrealHighPnl;
                            if (unrealLowPnl < trade.maxUnrealizedLoss) trade.maxUnrealizedLoss = unrealLowPnl;

                            if (c.high().compareTo(currentSL) >= 0) {
                                String exitLabel = targetLocked ? "TARGET_HIT" : (trailActivated ? "TRAIL_SL" : "SL_HIT");
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
                        }"""

content = content.replace(old_sell_best, new_sell_best)

with open(FILE, "w") as f:
    f.write(content)

# Verify changes
checks = [
    ("maxUnrealizedLoss = 0.0" in content, "Field maxUnrealizedLoss added"),
    ("maxUnrealizedProfit = 0.0" in content, "Field maxUnrealizedProfit added"),
    ("maxUnrealizedLoss" in content and "toMap" in content, "toMap includes maxUnrealizedLoss"),
    ("Track unrealised P&L extremes" in content, "BUY tracking added"),
    ("Track unrealised P&L extremes (SHORT" in content, "SELL tracking added"),
]

for ok, msg in checks:
    status = "OK" if ok else "MISSING"
    print(f"  [{status}] {msg}")

print(f"\nFile size: {len(content)} chars")
