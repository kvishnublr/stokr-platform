#!/usr/bin/env python3
"""Fix EntryManager: NRML -> CNC for equity, fix ZerodhaAdapter api key property"""

# Fix 1: EntryManager NRML -> CNC
f1 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/EntryManager.java"
with open(f1) as fp:
    code = fp.read()

old_product = '''            // Use NRML (positional) for daily strategies, MIS (intraday) for intraday
            String productType = "MIS";
            try {
                var strategy = strategyService.getStrategy(deployment.getStrategyId());
                if ("DAILY".equalsIgnoreCase(strategy.getTimeframe())) {
                    productType = "NRML";
                }
            } catch (Exception ignored) {}'''

new_product = '''            // Use CNC (delivery) for positional/daily, MIS for intraday
            String productType = "MIS";
            try {
                var strategy = strategyService.getStrategy(deployment.getStrategyId());
                if ("DAILY".equalsIgnoreCase(strategy.getTimeframe())) {
                    productType = "CNC";
                }
            } catch (Exception ignored) {}'''

code = code.replace(old_product, new_product)

with open(f1, 'w') as fp:
    fp.write(code)
print("EntryManager: NRML -> CNC fixed")

# Fix 2: ZerodhaAdapter api key property name
f2 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/broker/ZerodhaAdapter.java"
with open(f2) as fp:
    code2 = fp.read()

code2 = code2.replace(
    '@Value("${broker.zerodha.api-key:}")',
    '@Value("${ZERODHA_API_KEY:}")'
)

with open(f2, 'w') as fp:
    fp.write(code2)
print("ZerodhaAdapter: api key property fixed")
