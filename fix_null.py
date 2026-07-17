#!/usr/bin/env python3
"""Fix: edgeAfterCosts is primitive double, not Double - remove null checks"""

ctrl_path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(ctrl_path, 'r') as f:
    ctrl = f.read()

old = '''                // Top-2 per underlying: rank by edgeAfterCosts descending
                opps.sort((a, b) -> Double.compare(
                    b.edgeAfterCosts != null ? b.edgeAfterCosts : 0,
                    a.edgeAfterCosts != null ? a.edgeAfterCosts : 0));
                List<ArbitrageOpportunity> topOpps = opps.size() > 2 ? opps.subList(0, 2) : opps;
                if (opps.size() > 2) {
                    log.info("Ranking: {} opportunities for {}, taking top 2 (best edge {}, {})",
                        opps.size(), u,
                        topOpps.get(0).edgeAfterCosts != null ? topOpps.get(0).edgeAfterCosts : 0,
                        topOpps.get(1).edgeAfterCosts != null ? topOpps.get(1).edgeAfterCosts : 0);
                }'''

new = '''                // Top-2 per underlying: rank by edgeAfterCosts descending
                opps.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));
                List<ArbitrageOpportunity> topOpps = opps.size() > 2 ? opps.subList(0, 2) : opps;
                if (opps.size() > 2) {
                    log.info("Ranking: {} opportunities for {}, taking top 2 (best edge {}, {})",
                        opps.size(), u,
                        topOpps.get(0).edgeAfterCosts,
                        topOpps.get(1).edgeAfterCosts);
                }'''

if old in ctrl:
    ctrl = ctrl.replace(old, new)
    with open(ctrl_path, 'w') as f:
        f.write(ctrl)
    print("Fixed null checks for primitive double")
else:
    print("Could not find the pattern to replace")
