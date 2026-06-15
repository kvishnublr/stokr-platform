package com.stokr.execution.safety;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.service.PositionExitOrchestratorService;
import com.stokr.oms.repository.OmsExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFETY NET: auto-square-off orphaned broker positions.
 *
 * <p>An "orphan" is a real broker position the platform is NOT tracking internally
 * (broker qty != 0 while internal net qty == 0). These occur when a live order
 * fills at the broker but the DB record is lost — e.g. a transaction that places
 * the broker order then rolls back. Such positions are invisible to the normal
 * exit engine, so without this net they ride unmanaged until the broker's end-of-day
 * square-off (real, uncontrolled loss exposure). On 2026-06-15 a rollback bug
 * orphaned 18 live positions that had to be closed manually.</p>
 *
 * <p>Behaviour: every {@code interval-ms}, sync the broker, compare broker positions
 * to internal net qty, and for any symbol orphaned for at least {@code grace-seconds}
 * (so genuinely in-flight orders are not closed), reuse the proven
 * {@link PositionExitOrchestratorService#flattenSymbol} to place a controlled exit.
 * The grace window distinguishes a permanent orphan (internal never appears) from a
 * normal few-second order-in-flight window.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrphanPositionSquareOffService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final OmsExecutionRepository omsExecutionRepository;
    private final PositionExitOrchestratorService positionExitOrchestratorService;

    @Value("${stokr.execution.orphan-squareoff.enabled:true}")
    private boolean enabled;

    @Value("${stokr.execution.orphan-squareoff.grace-seconds:120}")
    private long graceSeconds;

    @Value("${stokr.strategy.primary-trader-user-id:}")
    private String primaryTraderUserIdRaw;

    /** symbol -> first time observed orphaned (grace timer). */
    private final Map<String, Instant> orphanFirstSeen = new ConcurrentHashMap<>();
    /** symbol -> last time we placed a square-off (cooldown to avoid double-placing). */
    private final Map<String, Instant> lastSquaredOff = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${stokr.execution.orphan-squareoff.interval-ms:60000}")
    public void squareOffOrphans() {
        if (!enabled) {
            return;
        }
        UUID userId = parsePrimaryTrader();
        if (userId == null) {
            return;
        }
        try {
            brokerPositionTruthService.syncUser(userId);
            BrokerPositionTruthSnapshot snap = brokerPositionTruthService.snapshot(userId);
            if (snap == null || snap.positions() == null) {
                orphanFirstSeen.clear();
                return;
            }

            Map<String, BigDecimal> internal = new HashMap<>();
            for (Object[] row : omsExecutionRepository.computeLiveNetQtyBySymbol(userId)) {
                if (row.length < 2 || row[0] == null || row[1] == null) {
                    continue;
                }
                internal.merge(
                        BrokerPositionTruthService.normalizeSymbol(String.valueOf(row[0])),
                        (BigDecimal) row[1],
                        BigDecimal::add);
            }

            Instant now = Instant.now();
            Set<String> currentOrphans = new HashSet<>();

            for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow pos : snap.positions()) {
                if (pos.brokerQty() == null || pos.brokerQty().signum() == 0) {
                    continue;
                }
                String sym = BrokerPositionTruthService.normalizeSymbol(pos.symbol());
                BigDecimal intQty = internal.getOrDefault(sym, BigDecimal.ZERO);
                if (intQty.signum() != 0) {
                    continue; // platform tracks this position — not an orphan
                }

                currentOrphans.add(sym);
                Instant firstSeen = orphanFirstSeen.computeIfAbsent(sym, k -> now);
                long ageSec = Duration.between(firstSeen, now).getSeconds();
                if (ageSec < graceSeconds) {
                    log.warn("orphan_squareoff.grace symbol={} brokerQty={} ageSec={} graceSec={}",
                            sym, pos.brokerQty(), ageSec, graceSeconds);
                    continue;
                }
                Instant last = lastSquaredOff.get(sym);
                if (last != null && Duration.between(last, now).getSeconds() < graceSeconds) {
                    continue; // already squared recently — wait for broker to reflect
                }

                try {
                    var result = positionExitOrchestratorService.flattenSymbol(
                            userId, pos.symbol(), "ORPHAN_AUTO_SQUAREOFF",
                            "auto-close orphaned broker position (internal=0)");
                    lastSquaredOff.put(sym, now);
                    log.error("orphan_squareoff.placed symbol={} brokerQty={} ageSec={} result={}",
                            sym, pos.brokerQty(), ageSec, result);
                } catch (Exception ex) {
                    log.error("orphan_squareoff.place_failed symbol={} brokerQty={} error={}",
                            sym, pos.brokerQty(), ex.getMessage());
                }
            }

            // stop tracking symbols that are no longer orphaned (closed / now tracked)
            orphanFirstSeen.keySet().retainAll(currentOrphans);
            lastSquaredOff.keySet().retainAll(currentOrphans);
        } catch (Exception e) {
            log.warn("orphan_squareoff.scan_failed error={}", e.getMessage());
        }
    }

    private UUID parsePrimaryTrader() {
        if (primaryTraderUserIdRaw == null || primaryTraderUserIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(primaryTraderUserIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
