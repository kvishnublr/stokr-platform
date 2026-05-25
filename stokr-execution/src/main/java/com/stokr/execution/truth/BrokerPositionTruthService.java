package com.stokr.execution.truth;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Broker position truth authority.
 *
 * Single source of truth for actual positions held with the broker.
 * Periodically fetches from Zerodha and reconciles against internal tracking.
 *
 * Key responsibility: Answer "what positions does the broker actually have?" regardless
 * of what our internal ledger says. Used for:
 * - Position initialization at startup
 * - Reconciliation after broker syncs
 * - External exit detection (broker closed position, we didn't order it)
 * - Position gap detection (our records don't match broker reality)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrokerPositionTruthService {

    private final KiteConnectBrokerAdapter brokerAdapter;
    private final PortfolioPositionRepository positionRepository;

    /**
     * Fetch authoritative position state from broker.
     * Queries Zerodha for holdings + open positions.
     * Returns: symbol -> (qty, entryPrice, currentPrice, PnL)
     */
    public Map<String, BrokerPosition> getAuthoritativePositions() {
        Map<String, BrokerPosition> result = new HashMap<>();

        try {
            // Fetch all holdings from broker
            List<Position> brokerPositions = brokerAdapter.getPositions();

            for (Position pos : brokerPositions) {
                if (pos.getQuantity() == null || pos.getQuantity().signum() == 0) {
                    continue; // Skip closed positions
                }

                String symbol = pos.getSymbol();
                BigDecimal qty = pos.getQuantity();
                BigDecimal entryPrice = pos.getAveragePrice() != null ? pos.getAveragePrice() : BigDecimal.ZERO;
                BigDecimal currentPrice = pos.getLtp() != null ? pos.getLtp() : entryPrice;
                BigDecimal realizedPnL = pos.getRealizedPnL() != null ? pos.getRealizedPnL() : BigDecimal.ZERO;
                BigDecimal unrealizedPnL = pos.getUnrealizedPnL() != null ? pos.getUnrealizedPnL() : BigDecimal.ZERO;

                BrokerPosition bp = new BrokerPosition(
                    symbol, qty, entryPrice, currentPrice, realizedPnL, unrealizedPnL, Instant.now()
                );
                result.put(symbol, bp);

                log.debug("broker.position symbol={} qty={} entry={} current={} pnl={}/{}",
                    symbol, qty, entryPrice, currentPrice, realizedPnL, unrealizedPnL);
            }

            log.info("broker.positions.fetched count={} timestamp={}", result.size(), Instant.now());
            return result;

        } catch (Exception ex) {
            log.error("broker.positions.fetch_failed {}", ex.getMessage());
            return Map.of(); // Return empty if fetch fails — don't mask error
        }
    }

    /**
     * Compare broker positions vs internal ledger.
     * Identifies gaps: positions we think we have but broker doesn't, and vice versa.
     */
    public PositionReconciliationReport reconcile() {
        Map<String, BrokerPosition> brokerPositions = getAuthoritativePositions();
        List<PortfolioPosition> internalPositions = positionRepository.findAllOpenPositions();

        PositionReconciliationReport report = new PositionReconciliationReport();
        report.setTimestamp(Instant.now());

        Map<String, PortfolioPosition> internalBySymbol = new HashMap<>();
        for (PortfolioPosition p : internalPositions) {
            internalBySymbol.put(p.getSymbol(), p);
        }

        // Check for positions in broker but not in our ledger (external acquisition)
        for (Map.Entry<String, BrokerPosition> entry : brokerPositions.entrySet()) {
            String symbol = entry.getKey();
            BrokerPosition bp = entry.getValue();

            if (!internalBySymbol.containsKey(symbol)) {
                report.addExternalPosition(symbol, bp.getQuantity(), bp.getEntryPrice());
                log.warn("reconciliation.external_position symbol={} qty={}", symbol, bp.getQuantity());
            }
        }

        // Check for positions in our ledger but not in broker (external exit)
        for (Map.Entry<String, PortfolioPosition> entry : internalBySymbol.entrySet()) {
            String symbol = entry.getKey();
            PortfolioPosition ip = entry.getValue();

            if (!brokerPositions.containsKey(symbol)) {
                report.addMissingPosition(symbol, ip.getQuantity(), ip.getAverageEntryPrice());
                log.warn("reconciliation.missing_position symbol={} qty={}", symbol, ip.getQuantity());
            }
        }

        // Check for qty mismatches
        for (Map.Entry<String, PortfolioPosition> entry : internalBySymbol.entrySet()) {
            String symbol = entry.getKey();
            PortfolioPosition ip = entry.getValue();
            BrokerPosition bp = brokerPositions.get(symbol);

            if (bp != null && ip.getQuantity().compareTo(bp.getQuantity()) != 0) {
                report.addQtyMismatch(symbol, ip.getQuantity(), bp.getQuantity());
                log.warn("reconciliation.qty_mismatch symbol={} internal={} broker={}",
                    symbol, ip.getQuantity(), bp.getQuantity());
            }
        }

        if (report.hasDiscrepancies()) {
            log.error("reconciliation.gaps external={} missing={} qty_mismatches={}",
                report.getExternalPositions().size(),
                report.getMissingPositions().size(),
                report.getQtyMismatches().size());
        } else {
            log.info("reconciliation.clean all positions match");
        }

        return report;
    }

    /**
     * Detect external exit: position we think is open was closed by broker/market.
     */
    public Optional<String> detectExternalExit() {
        Map<String, BrokerPosition> brokerPositions = getAuthoritativePositions();
        List<PortfolioPosition> internalPositions = positionRepository.findAllOpenPositions();

        for (PortfolioPosition ip : internalPositions) {
            if (!brokerPositions.containsKey(ip.getSymbol())) {
                return Optional.of(ip.getSymbol());
            }
        }

        return Optional.empty();
    }

    /**
     * Authoritative broker position snapshot.
     */
    public record BrokerPosition(
        String symbol,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal currentPrice,
        BigDecimal realizedPnL,
        BigDecimal unrealizedPnL,
        Instant fetchedAt
    ) {}
}
