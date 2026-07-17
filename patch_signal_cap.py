#!/usr/bin/env python3
"""Patch SignalProcessor.java to add daily signal cap (max 5 signals per deployment per day for DAILY strategies)."""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"

with open(path, 'r') as f:
    content = f.read()

changed = False

# 1. Add MAX_DAILY_SIGNALS constant after the IST field
old_const = "    private static final ZoneId IST = ZoneId.of(\"Asia/Kolkata\");"
new_const = "    private static final ZoneId IST = ZoneId.of(\"Asia/Kolkata\");\n    private static final int MAX_DAILY_SIGNALS_PER_DEPLOYMENT = 5;"
if "MAX_DAILY_SIGNALS_PER_DEPLOYMENT" not in content:
    content = content.replace(old_const, new_const)
    print("1. Added MAX_DAILY_SIGNALS_PER_DEPLOYMENT constant")
    changed = True
else:
    print("1. Constant already exists, skipping")

# 2. Add daily count check after the alreadyRejected check in processDailyDeployment
old_dedup = """                // Also skip if we already have a REJECTED signal today (prevent spam)
                boolean alreadyRejected = signalRepository
                    .findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                        deployment.getId(), symbol, "REJECTED")
                    .isPresent();
                if (alreadyRejected) continue;"""

new_dedup = """                // Also skip if we already have a REJECTED signal today (prevent spam)
                boolean alreadyRejected = signalRepository
                    .findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                        deployment.getId(), symbol, "REJECTED")
                    .isPresent();
                if (alreadyRejected) continue;

                // Daily signal cap: max N signals per deployment per day
                long todaySignalCount = signalRepository.countByDeploymentIdAndCreatedAtAfter(
                    deployment.getId(), LocalDate.now(IST).atStartOfDay(IST).toInstant());
                if (todaySignalCount >= MAX_DAILY_SIGNALS_PER_DEPLOYMENT) {
                    log.info("Daily signal cap reached for deployment {} ({} >= {}), skipping {}",
                        deployment.getId(), todaySignalCount, MAX_DAILY_SIGNALS_PER_DEPLOYMENT, symbol);
                    break;
                }"""

if "todaySignalCount" not in content:
    content = content.replace(old_dedup, new_dedup)
    print("2. Added daily signal cap check in processDailyDeployment")
    changed = True
else:
    print("2. Daily signal cap already exists, skipping")

if changed:
    with open(path, 'w') as f:
        f.write(content)
    print("SignalProcessor.java patched successfully!")
else:
    print("No changes needed.")
