#!/usr/bin/env python3
"""Add OptionArbHistoryService to OptionArbitrageController"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(f) as fp:
    code = fp.read()

# Add history service import and field
code = code.replace(
    "    private final ZerodhaSpotPriceFetcher spotFetcher;",
    "    private final ZerodhaSpotPriceFetcher spotFetcher;\n    private final OptionArbHistoryService historyService;"
)

# Update constructor
code = code.replace(
    "    public OptionArbitrageController(OptionChainService optionChainService,\n                                      ZerodhaSpotPriceFetcher spotFetcher) {\n        this.optionChainService = optionChainService;\n        this.spotFetcher = spotFetcher;\n    }",
    "    public OptionArbHistoryService historyService;\n\n    public OptionArbitrageController(OptionChainService optionChainService,\n                                      ZerodhaSpotPriceFetcher spotFetcher,\n                                      OptionArbHistoryService historyService) {\n        this.optionChainService = optionChainService;\n        this.spotFetcher = spotFetcher;\n        this.historyService = historyService;\n    }"
)

# Add save call after each scan in the NIFTY block
code = code.replace(
    "                    scanCache.put(\"NIFTY\", niftyOpps);\n                    scanTimestamp.put(\"NIFTY\", System.currentTimeMillis());",
    "                    scanCache.put(\"NIFTY\", niftyOpps);\n                    scanTimestamp.put(\"NIFTY\", System.currentTimeMillis());\n                    historyService.saveOpportunities(niftyOpps, \"NIFTY\");"
)

# Add save call after each scan in the BANKNIFTY block
code = code.replace(
    "                    scanCache.put(\"BANKNIFTY\", bankNiftyOpps);\n                    scanTimestamp.put(\"BANKNIFTY\", System.currentTimeMillis());",
    "                    scanCache.put(\"BANKNIFTY\", bankNiftyOpps);\n                    scanTimestamp.put(\"BANKNIFTY\", System.currentTimeMillis());\n                    historyService.saveOpportunities(bankNiftyOpps, \"BANKNIFTY\");"
)

with open(f, 'w') as fp:
    fp.write(code)
print("Controller updated with history service")
