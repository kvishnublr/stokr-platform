#!/usr/bin/env python3
"""Fix: Only save top-2 opportunities per underlying to DB (not all 10-15)"""

ctrl_path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(ctrl_path, 'r') as f:
    ctrl = f.read()

# Change the cooldown loop from iterating `opps` to `topOpps`
old = '''                // Filter out opportunities that are within cooldown period
                List<ArbitrageOpportunity> freshOpps = new ArrayList<>();
                for (ArbitrageOpportunity opp : opps) {'''

new = '''                // Filter out top opportunities that are within cooldown period
                List<ArbitrageOpportunity> freshOpps = new ArrayList<>();
                for (ArbitrageOpportunity opp : topOpps) {'''

if old in ctrl:
    ctrl = ctrl.replace(old, new)
    with open(ctrl_path, 'w') as f:
        f.write(ctrl)
    print("Fixed: cooldown/save loop now only processes top-2 per underlying")
else:
    print("Could not find pattern to replace")
    # Show what's around cooldown loop
    idx = ctrl.find("Filter out opportunities that are within cooldown")
    if idx > 0:
        print(ctrl[idx:idx+200])
