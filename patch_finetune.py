#!/usr/bin/env python3
"""
Patch: Option Arb fine-tuning
1. Spread gate: 5% → 2%
2. DTE filter: 3-21 days only
3. Volume/OI minimums: volume >= 100, OI >= 100
4. Top-2 per underlying in /scan endpoint
"""

import re

# === PATCH OptionChainService.java ===
svc_path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(svc_path, 'r') as f:
    svc = f.read()

# 1. Spread gate 5% → 2%
svc = svc.replace(
    "private static final double MAX_SPREAD_PCT = 5.0;",
    "private static final double MAX_SPREAD_PCT = 2.0;"
)

# 2. Add DTE + volume/OI constants
svc = svc.replace(
    "private static final int COOLDOWN_SECONDS = 60;",
    "private static final int COOLDOWN_SECONDS = 60;\n" +
    "    private static final int MIN_DTE = 3;\n" +
    "    private static final int MAX_DTE = 21;\n" +
    "    private static final long MIN_VOLUME = 100;\n" +
    "    private static final long MIN_OI = 100;"
)

# 3. Add DTE filter at start of scanOptionChain, after daysToExpiry calculation
old_dte = '''            if (daysToExpiry < 0) {
                log.warn("No future expiry found for {}, skipping scan", underlying);
                return opportunities;
            }'''

new_dte = '''            if (daysToExpiry < 0) {
                log.warn("No future expiry found for {}, skipping scan", underlying);
                return opportunities;
            }

            if (daysToExpiry < MIN_DTE || daysToExpiry > MAX_DTE) {
                log.info("DTE {} outside [{}, {}] for {}, skipping scan", (int) daysToExpiry, MIN_DTE, MAX_DTE, underlying);
                return opportunities;
            }'''

svc = svc.replace(old_dte, new_dte)

# 4. Add volume/OI check after the existing price/spread checks
old_check = '''                if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;
                if (isSpreadTooWide(ceQuote) || isSpreadTooWide(peQuote)) continue;

                validStrikes++;'''

new_check = '''                if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;
                if (isSpreadTooWide(ceQuote) || isSpreadTooWide(peQuote)) continue;

                // Liquidity gate: volume + OI minimums
                if (ceQuote.volume < MIN_VOLUME && peQuote.volume < MIN_VOLUME) continue;
                if (ceQuote.openInterest < MIN_OI && peQuote.openInterest < MIN_OI) continue;

                validStrikes++;'''

svc = svc.replace(old_check, new_check)

# 5. Log the liquidity filter
old_log = '''            log.info("Analyzed {} valid strikes for {}, found {} opportunities", validStrikes, underlying, opportunities.size());'''
new_log = '''            log.info("Analyzed {} valid strikes for {}, found {} opportunities (DTE {}, spread<2%, vol>={}, OI>{})",
                validStrikes, underlying, opportunities.size(), (int) daysToExpiry, MIN_VOLUME, MIN_OI);'''
svc = svc.replace(old_log, new_log)

with open(svc_path, 'w') as f:
    f.write(svc)
print("Patched OptionChainService.java")

# === PATCH OptionArbitrageController.java ===
ctrl_path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(ctrl_path, 'r') as f:
    ctrl = f.read()

# Add top-2 per underlying limit after scanOptionChain call
# Find the pattern where opps are added to allOpportunities
old_add = '''                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut);
                allOpportunities.addAll(opps);
                scanCache.put(u, opps);'''

new_add = '''                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut);

                // Top-2 per underlying: rank by edgeAfterCosts descending
                opps.sort((a, b) -> Double.compare(
                    b.edgeAfterCosts != null ? b.edgeAfterCosts : 0,
                    a.edgeAfterCosts != null ? a.edgeAfterCosts : 0));
                List<ArbitrageOpportunity> topOpps = opps.size() > 2 ? opps.subList(0, 2) : opps;
                if (opps.size() > 2) {
                    log.info("Ranking: {} opportunities for {}, taking top 2 (best edge ₹{:.0f}, ₹{:.0f})",
                        opps.size(), u, topOpps.get(0).edgeAfterCosts, topOpps.get(1).edgeAfterCosts);
                }

                allOpportunities.addAll(topOpps);
                scanCache.put(u, topOpps);'''

# Fix the f-string issue - use format-style logging
new_add = '''                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut);

                // Top-2 per underlying: rank by edgeAfterCosts descending
                opps.sort((a, b) -> Double.compare(
                    b.edgeAfterCosts != null ? b.edgeAfterCosts : 0,
                    a.edgeAfterCosts != null ? a.edgeAfterCosts : 0));
                List<ArbitrageOpportunity> topOpps = opps.size() > 2 ? opps.subList(0, 2) : opps;
                if (opps.size() > 2) {
                    log.info("Ranking: {} opportunities for {}, taking top 2 (best edge {}, {})",
                        opps.size(), u,
                        topOpps.get(0).edgeAfterCosts != null ? topOpps.get(0).edgeAfterCosts : 0,
                        topOpps.get(1).edgeAfterCosts != null ? topOpps.get(1).edgeAfterCosts : 0);
                }

                allOpportunities.addAll(topOpps);
                scanCache.put(u, topOpps);'''

if old_add in ctrl:
    ctrl = ctrl.replace(old_add, new_add)
    print("Patched OptionArbitrageController.java (top-2 per underlying)")
else:
    print("WARNING: Could not find patch target in controller")
    # Try to find what's there
    idx = ctrl.find("scanOptionChain(u, spot, fut)")
    if idx > 0:
        print(f"Found scanOptionChain at position {idx}")
        print(ctrl[idx-50:idx+200])

with open(ctrl_path, 'w') as f:
    f.write(ctrl)

print("Done!")
