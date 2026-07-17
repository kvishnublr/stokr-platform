#!/usr/bin/env python3
"""Patch ExecutionEngine with crash-resilient squareOffAll and unrealized P&L calc."""

FILE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java"

with open(FILE, "r") as f:
    content = f.read()

# 1. Add import for ApplicationReadyEvent and EventListener
content = content.replace(
    "import lombok.extern.slf4j.Slf4j;",
    "import lombok.extern.slf4j.Slf4j;\nimport org.springframework.boot.context.event.ApplicationReadyEvent;\nimport org.springframework.context.event.EventListener;"
)

# 2. Add import for SignalRepository (already present) and PositionRepository
content = content.replace(
    "import java.math.BigDecimal;",
    "import com.stokr.oms.PositionRepository;\nimport java.math.BigDecimal;"
)

# 3. Add PositionRepository field injection
content = content.replace(
    "    private final SignalRepository           signalRepository;",
    "    private final SignalRepository           signalRepository;\n    private final com.stokr.oms.PositionRepository positionRepository;"
)

# 4. Add EOD time check and crash-resilient startup method
CRASH_RESILIENT = """
    /**
     * On startup: if market is closed (after EOD) and positions are still open,
     * the backend crashed during market hours. Square them off immediately.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("=== STARTUP: checking for missed EOD square-off ===");
        try {
            List<Deployment> activeDeployments = deploymentService.getAllActiveDeployments();
            LocalTime now = LocalTime.now(IST);
            boolean pastEod = now.isAfter(EOD_SQUAREOFF);
            
            for (Deployment deployment : activeDeployments) {
                if (!deployment.isLive()) continue;
                List<Position> open = positionService.getOpenPositions(deployment.getId());
                if (open.isEmpty()) continue;
                
                if (pastEod) {
                    // Market already closed — these positions survived overnight, must be squared off
                    log.warn("CRASH RECOVERY: deployment {} has {} open positions after EOD — squaring off",
                            deployment.getId(), open.size());
                    squareOffAll(deployment);
                } else {
                    // Market still open — rebuild bestPriceMap from existing signals
                    log.info("REBUILD TRAIL: deployment {} has {} open positions — rebuilding trail state",
                            deployment.getId(), open.size());
                    for (Position pos : open) {
                        signalRepository.findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                                deployment.getId(), pos.getSymbol(), "EXECUTED")
                            .ifPresent(s -> {
                                if (s.getEntryPrice() != null) {
                                    bestPriceMap.put(pos.getId(), s.getEntryPrice());
                                    log.info("  Rebuilt trail for {} at entry {}", pos.getSymbol(), s.getEntryPrice());
                                }
                            });
                    }
                }
            }
        } catch (Exception e) {
            log.error("Startup reconciliation failed", e);
        }
        log.info("=== STARTUP RECONCILIATION COMPLETE ===");
    }
"""

# Insert after the bestPriceMap declaration
content = content.replace(
    "    // Trailing SL: best price seen per position since entry — reset on square-off\n    private final Map<Long, BigDecimal> bestPriceMap = new ConcurrentHashMap<>();",
    "    // Trailing SL: best price seen per position since entry — reset on square-off\n    private final Map<Long, BigDecimal> bestPriceMap = new ConcurrentHashMap<>();\n" + CRASH_RESILIENT
)

# 5. Add unrealized P&L update in processExits — after getting LTP, update position
OLD_PROCESS_EXIT_BLOCK = """                // Update trailing best price
                BigDecimal best = bestPriceMap.merge(pos.getId(), ltp, BigDecimal::max);"""

NEW_PROCESS_EXIT_BLOCK = """                // Update unrealized P&L with live price
                try {
                    BigDecimal unrealized = ltp.subtract(pos.getAvgPrice())
                        .multiply(BigDecimal.valueOf(pos.getQuantity()));
                    pos.setUnrealizedPnl(unrealized);
                    positionRepository.save(pos);
                } catch (Exception e) {
                    log.debug("Failed to update unrealized P&L for {}: {}", pos.getSymbol(), e.getMessage());
                }

                // Update trailing best price
                BigDecimal best = bestPriceMap.merge(pos.getId(), ltp, BigDecimal::max);"""

content = content.replace(OLD_PROCESS_EXIT_BLOCK, NEW_PROCESS_EXIT_BLOCK)

# 6. Reset bestPriceMap for squared-off positions (already done in squareOff)

with open(FILE, "w") as f:
    f.write(content)

print("Patched ExecutionEngine with:")
print("  1. Crash-resilient startup squareOffAll (past EOD → square off)")
print("  2. Trail state rebuild on startup (open positions → bestPriceMap)")
print("  3. Unrealized P&L calc on every exit check cycle")

# Verify
import re
startup_count = content.count("reconcileOnStartup")
unrealized_count = content.count("setUnrealizedPnl")
print(f"  Verify: reconcileOnStartup refs={startup_count}, setUnrealizedPnl refs={unrealized_count}")
