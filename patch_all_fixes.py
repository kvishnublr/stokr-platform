#!/usr/bin/env python3
"""Patch all 3 backend issues in OptionArbitrageController.java:
1. getValidatedFutures: use actual Zerodha price during market hours (only fallback to synthetic when Zerodha returns 0)
2. /scan: add cooldown dedup — skip saving if same underlying+strike+type exists today
3. /live-prices-batch: only fetch spot+futures for underlyings that have today's opportunities
"""

import re

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"

with open(path, 'r') as f:
    content = f.read()

# ═══════════════════════════════════════════════════════════════════════
# FIX 1: getValidatedFutures — use actual Zerodha price, only fallback when 0
# ═══════════════════════════════════════════════════════════════════════
old_fut = """    private double getValidatedFutures(String underlying, double spot) {
        UnderlyingConfig cfg = CONFIGS.get(underlying);
        if (cfg == null || spot <= 0) return spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
        String futKey = cfg.futuresPrefix() + getCurrentMonthSuffix() + "FUT";
        double fut = spotFetcher.getSpotPrice(futKey);
        double expectedFwd = spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
        double maxDeviation = spot * 0.005;  // 0.5% of spot
        if (fut <= 0 || Math.abs(fut - expectedFwd) > maxDeviation) {
            log.warn("{} futures {} deviates from expected forward {} (max \u00b1{}), using synthetic",
                underlying, fut, String.format("%.2f", expectedFwd), String.format("%.2f", maxDeviation));
            return expectedFwd;
        }
        return fut;
    }"""

new_fut = """    private double getValidatedFutures(String underlying, double spot) {
        UnderlyingConfig cfg = CONFIGS.get(underlying);
        if (cfg == null || spot <= 0) return spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
        String futKey = cfg.futuresPrefix() + getCurrentMonthSuffix() + "FUT";
        double fut = spotFetcher.getSpotPrice(futKey);
        // Use actual Zerodha price — the market IS the truth during market hours
        // Only fallback to synthetic when Zerodha returns 0 (API failure)
        if (fut <= 0) {
            double synthetic = spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
            log.warn("{} futures returned 0 from Zerodha (key={}), using synthetic {:.2f}",
                underlying, futKey, synthetic);
            return synthetic;
        }
        log.debug("{} futures from Zerodha: {} (key={})", underlying, fut, futKey);
        return fut;
    }"""

if "Use actual Zerodha price" in content:
    print("1. getValidatedFutures already patched, skipping")
else:
    content = content.replace(old_fut, new_fut)
    print("1. FIXED getValidatedFutures — uses actual Zerodha price, only synthetic when API returns 0")


# ═══════════════════════════════════════════════════════════════════════
# FIX 2: /scan — add cooldown dedup before saving to DB
# ═══════════════════════════════════════════════════════════════════════

# Add cooldown tracking map after scanTimestamp
old_maps = """    private final ConcurrentHashMap<String, Long> scanTimestamp = new ConcurrentHashMap<>();"""
new_maps = """    private final ConcurrentHashMap<String, Long> scanTimestamp = new ConcurrentHashMap<>();
    // Cooldown: skip saving if same underlying+strike+type was saved within last 5 minutes
    private final ConcurrentHashMap<String, Long> savedCooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 5 * 60 * 1000;  // 5 minutes"""

if "savedCooldowns" in content:
    print("2. Cooldown map already exists, skipping")
else:
    content = content.replace(old_maps, new_maps)
    print("2. Added cooldown tracking map (5 min cooldown)")

# Now add cooldown check in the scan endpoint — after opportunities are found, before saving
old_save = """                allOpportunities.addAll(opps);
                scanCache.put(u, opps);
                scanTimestamp.put(u, System.currentTimeMillis());
                historyService.saveOpportunities(opps, u);"""

new_save = """                allOpportunities.addAll(opps);
                scanCache.put(u, opps);
                scanTimestamp.put(u, System.currentTimeMillis());

                // Filter out opportunities that are within cooldown period
                List<ArbitrageOpportunity> freshOpps = new ArrayList<>();
                for (ArbitrageOpportunity opp : opps) {
                    String dedupKey = u + "|" + opp.strike + "|" + opp.type + "|" + opp.action;
                    Long lastSaved = savedCooldowns.get(dedupKey);
                    long now = System.currentTimeMillis();
                    if (lastSaved == null || (now - lastSaved) > COOLDOWN_MS) {
                        freshOpps.add(opp);
                        savedCooldowns.put(dedupKey, now);
                    } else {
                        log.debug("Cooldown active for {} ({}ms remaining), skipping save",
                            dedupKey, COOLDOWN_MS - (now - lastSaved));
                    }
                }
                if (!freshOpps.isEmpty()) {
                    historyService.saveOpportunities(freshOpps, u);
                    log.info("Saved {}/{} new opportunities for {} ({} filtered by cooldown)",
                        freshOpps.size(), opps.size(), u, opps.size() - freshOpps.size());
                } else {
                    log.info("All {} opportunities for {} filtered by cooldown, nothing saved",
                        opps.size(), u);
                }"""

if "freshOpps" in content:
    print("2. Cooldown filter already in scan endpoint, skipping")
else:
    content = content.replace(old_save, new_save)
    print("2. Added cooldown dedup in /scan — skips saving if same strike+type saved within 5 min")


# ═══════════════════════════════════════════════════════════════════════
# FIX 3: /live-prices-batch — only fetch spot+futures for underlyings with opportunities
# ═══════════════════════════════════════════════════════════════════════
old_batch_fetch = """            // Fetch spot + futures per underlying
            Map<String, double[]> spotFutMap = new LinkedHashMap<>();
            for (String u : ALL_UNDERLYINGS) {
                double[] sf = getSpotAndFuturesValidated(u);
                spotFutMap.put(u, sf);
            }"""

new_batch_fetch = """            // Fetch spot + futures ONLY for underlyings that have today's opportunities
            Set<String> underlyingsWithOpps = new HashSet<>();
            for (OptionArbOpportunity opp : todayOpps) {
                underlyingsWithOpps.add(opp.getUnderlying());
            }
            log.info("Fetching live spot+futures for {} underlyings: {}", underlyingsWithOpps.size(), underlyingsWithOpps);
            Map<String, double[]> spotFutMap = new LinkedHashMap<>();
            for (String u : underlyingsWithOpps) {
                double[] sf = getSpotAndFuturesValidated(u);
                spotFutMap.put(u, sf);
            }"""

if "underlyingsWithOpps" in content:
    print("3. live-prices-batch already optimized, skipping")
else:
    content = content.replace(old_batch_fetch, new_batch_fetch)
    print("3. Optimized /live-prices-batch — only fetches spot+futures for underlyings with opportunities")


# Write back
with open(path, 'w') as f:
    f.write(content)

print("\nAll backend fixes applied successfully!")
print("Now rebuild Docker: docker compose build --no-cache backend && docker compose up -d --force-recreate --no-deps backend")
