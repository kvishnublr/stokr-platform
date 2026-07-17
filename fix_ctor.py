#!/usr/bin/env python3
"""Fix: Add executionService to constructor"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(path, 'r') as f:
    content = f.read()

old_ctor = """    public OptionArbitrageController(OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher,
                                      OptionArbHistoryService historyService,
                                      CalendarSpreadService calendarSpreadService,
                                      VolSurfaceService volSurfaceService) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.historyService = historyService;
        this.calendarSpreadService = calendarSpreadService;
        this.volSurfaceService = volSurfaceService;
    }"""

new_ctor = """    public OptionArbitrageController(OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher,
                                      OptionArbHistoryService historyService,
                                      CalendarSpreadService calendarSpreadService,
                                      VolSurfaceService volSurfaceService,
                                      OptionArbExecutionService executionService) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.historyService = historyService;
        this.calendarSpreadService = calendarSpreadService;
        this.volSurfaceService = volSurfaceService;
        this.executionService = executionService;
    }"""

if old_ctor in content:
    content = content.replace(old_ctor, new_ctor)
    with open(path, 'w') as f:
        f.write(content)
    print("Fixed constructor")
else:
    print("Could not find constructor pattern")
