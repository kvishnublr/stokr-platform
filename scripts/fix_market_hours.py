#!/usr/bin/env python3
"""Add NSE market hours check to OptionArbHistoryService.saveOpportunities"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbHistoryService.java"
with open(f) as fp:
    code = fp.read()

# Add market hours check at the start of saveOpportunities
old = """    public void saveOpportunities(List<ArbitrageOpportunity> opportunities, String underlying) {
        if (opportunities == null || opportunities.isEmpty()) return;

        for (ArbitrageOpportunity opp : opportunities) {"""

new = """    public void saveOpportunities(List<ArbitrageOpportunity> opportunities, String underlying) {
        if (opportunities == null || opportunities.isEmpty()) return;

        // Only save during NSE market hours (9:15 AM - 3:30 PM IST)
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);
        if (now.isBefore(marketOpen) || now.isAfter(marketClose)) {
            log.debug("Skipping history save — outside NSE market hours ({} IST)", now);
            return;
        }

        for (ArbitrageOpportunity opp : opportunities) {"""

code = code.replace(old, new)

with open(f, 'w') as fp:
    fp.write(code)
print("Added market hours check")
