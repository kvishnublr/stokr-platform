#!/usr/bin/env python3
"""Fix parity deviation formula and cost model for NSE options"""
import re

# Fix 1: BlackScholesCalculator - fix syntheticFutures formula
f1 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/BlackScholesCalculator.java"
with open(f1) as fp:
    code = fp.read()

# Fix syntheticFutures - use futures option parity
code = code.replace(
    """    /**
     * Put-Call Parity: synthetic futures price from call and put
     * Synthetic = Call - Put + K * e^(-rT)
     */
    public static double syntheticFutures(double callPrice, double putPrice, double K, double r, double T) {
        return callPrice - putPrice + K * Math.exp(-r * T);
    }""",
    """    /**
     * Put-Call Parity for FUTURES options: C - P = e^(-rT) * (F - K)
     * => Synthetic F = K + (C - P) * e^(rT)
     */
    public static double syntheticFutures(double callPrice, double putPrice, double K, double r, double T) {
        return K + (callPrice - putPrice) * Math.exp(r * T);
    }"""
)

with open(f1, 'w') as fp:
    fp.write(code)
print("Fixed syntheticFutures formula")

# Fix 2: OptionChainService - fix cost model
f2 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(f2) as fp:
    code = fp.read()

# Fix calculateParityEdge with realistic NSE costs
old_edge = """    private double calculateParityEdge(double parityDev, String underlying) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double grossEdge = rawEdge * lotSize;
        double costs = grossEdge * 0.0005 + 50;
        return grossEdge - costs;
    }"""

new_edge = """    private double calculateParityEdge(double parityDev, String underlying) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double grossEdge = rawEdge * lotSize;

        // Realistic NSE transaction costs for 3-leg arb (per lot, round-trip):
        // Entry: STT on sell option 0.1% + STT on sell fut 0.02% + brokerage x3 + exchange + SEBI
        // Exit: same STT + brokerage x3 + exchange + SEBI
        // Approximate as flat per-leg cost
        double sttSellOption = 0.001;   // 0.1% on sell side of option premium
        double sttFutures = 0.0002;     // 0.02% on futures (both sides)
        double brokeragePerOrder = 20;  // flat ₹20/order
        double exchangeChargePct = 0.0000345; // 0.00345%
        double sebiChargePct = 0.000001;      // 0.0001%

        // Use average premium ~₹300 for STT estimation
        double avgPremium = 300;
        double underlyingAvgPrice = "BANKNIFTY".equals(underlying) ? 52000 : 24000;

        // Entry costs
        double sttEntry = avgPremium * sttSellOption * lotSize + underlyingAvgPrice * sttFutures * lotSize;
        double brokerageEntry = brokeragePerOrder * 3;
        double exchangeEntry = (avgPremium * lotSize + underlyingAvgPrice * lotSize) * exchangeChargePct;
        double sebiEntry = (avgPremium * lotSize + underlyingAvgPrice * lotSize) * sebiChargePct;
        double entryCosts = sttEntry + brokerageEntry + exchangeEntry + sebiEntry;

        // Exit costs (same structure)
        double exitCosts = entryCosts * 0.8; // slightly less on exit (no STT on buy option)

        double totalCosts = entryCosts + exitCosts;
        return grossEdge - totalCosts;
    }"""

code = code.replace(old_edge, new_edge)

# Also raise the minimum edge threshold to filter out false positives
code = code.replace(
    "private static final double MIN_EDGE_AFTER_COSTS = 200.0;",
    "private static final double MIN_EDGE_AFTER_COSTS = 500.0;"
)

with open(f2, 'w') as fp:
    fp.write(code)
print("Fixed cost model and threshold")
