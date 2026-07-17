#!/usr/bin/env python3
"""Improve cost model to use actual premiums + pass them to calculateParityEdge"""

# Fix OptionChainService: pass cePrice, pePrice, futuresPrice to calculateParityEdge
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(f) as fp:
    code = fp.read()

# Fix the call site to pass more params
code = code.replace(
    "double edgeAfterCosts = calculateParityEdge(parityDev, underlying);",
    "double edgeAfterCosts = calculateParityEdge(parityDev, underlying, ceQuote.lastPrice, peQuote.lastPrice, futuresPrice);"
)

# Fix the method signature and body
old_method = """    private double calculateParityEdge(double parityDev, String underlying) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double grossEdge = rawEdge * lotSize;

        // Realistic NSE transaction costs for 3-leg arb (per lot, round-trip):
        // Entry: STT on sell option 0.1% + STT on sell fut 0.02% + brokerage x3 + exchange + SEBI
        // Exit: same STT + brokerage x3 + exchange + SEBI
        // Approximate as flat per-leg cost
        double sttSellOption = 0.001;   // 0.1% on sell side of option premium
        double sttFutures = 0.0002;     // 0.02% on futures (both sides)
        double brokeragePerOrder = 20;  // flat Rs.20/order
        double exchangeChargePct = 0.0000345; // 0.00345%
        double sebiChargePct = 0.000001;      // 0.0001%

        // Use average premium ~Rs.300 for STT estimation
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

new_method = """    private double calculateParityEdge(double parityDev, String underlying, double cePrice, double pePrice, double futuresPrice) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double grossEdge = rawEdge * lotSize;

        // Realistic NSE transaction costs for 3-leg arb (per lot, round-trip):
        // CONVERSION: Buy CE + Sell PE + Sell FUT
        //   Entry STT: Sell PE (0.1% of PE prem * lot) + Sell FUT (0.02% of FUT * lot)
        //   Exit STT: Sell CE (0.1% of CE prem * lot) + Buy FUT (0.02% of FUT * lot)
        // REVERSAL: Sell CE + Buy PE + Buy FUT
        //   Entry STT: Sell CE (0.1% of CE prem * lot)
        //   Exit STT: Sell PE (0.1% of PE prem * lot) + Buy FUT (0.02% of FUT * lot) + Sell FUT (0.02% of FUT * lot)
        double sttRate = 0.001;     // 0.1% on sell-side option premium
        double sttFutRate = 0.0002; // 0.02% on futures
        double brokeragePerOrder = 20;
        double exchangePct = 0.0000345;
        double sebiPct = 0.000001;

        // Entry STT: one option sell + one futures sell
        double sttEntry = pePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        // Exit STT: one option sell + one futures buy
        double sttExit = cePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        double totalSTT = sttEntry + sttExit;

        // Brokerage: 6 orders (3 entry + 3 exit)
        double totalBrokerage = brokeragePerOrder * 6;

        // Exchange + SEBI on total turnover (both legs)
        double turnover = (cePrice + pePrice + futuresPrice) * lotSize * 2; // entry + exit
        double totalExchange = turnover * exchangePct;
        double totalSebi = turnover * sebiPct;
        double totalGst = (totalBrokerage + totalExchange) * 0.18;

        double totalCosts = totalSTT + totalBrokerage + totalExchange + totalSebi + totalGst;
        return grossEdge - totalCosts;
    }"""

code = code.replace(old_method, new_method)

with open(f, 'w') as fp:
    fp.write(code)
print("Updated cost model with actual premiums")
