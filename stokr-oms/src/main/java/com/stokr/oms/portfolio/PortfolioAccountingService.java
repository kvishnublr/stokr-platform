package com.stokr.oms.portfolio;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioDailySummary;
import com.stokr.oms.domain.PortfolioPnlSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.PortfolioDailySummaryRepository;
import com.stokr.oms.repository.PortfolioPnlSnapshotRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.util.OmsSymbolNormalizer;
import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.common.events.StrategyPnlUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioAccountingService {

    private final OmsExecutionRepository executionRepository;
    private final PortfolioPositionRepository positionRepository;
    private final PortfolioPnlSnapshotRepository pnlSnapshotRepository;
    private final PortfolioDailySummaryRepository dailySummaryRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Incremental hook after a trade insert; recomputes symbol position from all trades for correctness.
     */
    @Transactional
    public void applyFill(UUID userId, String symbol) {
        applyFill(userId, symbol, null);
    }

    @Transactional
    public void applyFill(UUID userId, String symbol, String strategyKey) {
        BigDecimal prevRealized = findActivePositionByNormalizedSymbol(userId, symbol)
                .map(p -> nullSafe(p.getRealizedPnl()))
                .orElse(BigDecimal.ZERO);
        rebuildSymbol(userId, symbol, strategyKey);
        BigDecimal newRealized = findActivePositionByNormalizedSymbol(userId, symbol)
                .map(p -> nullSafe(p.getRealizedPnl()))
                .orElse(BigDecimal.ZERO);
        recordSnapshot(userId, ZoneId.of("Asia/Kolkata"));
        if (strategyKey != null) {
            BigDecimal delta = newRealized.subtract(prevRealized);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                eventPublisher.publishEvent(new StrategyPnlUpdateEvent(strategyKey, delta));
            }
        }
    }

    @Transactional
    public void rebuildSymbol(UUID userId, String symbol) {
        rebuildSymbol(userId, symbol, null);
    }

    @Transactional
    public void rebuildSymbol(UUID userId, String symbol, String strategyKey) {
        String portfolioSymbol = resolvePortfolioSymbol(userId, symbol);
        List<String> variants = symbolVariants(symbol);
        applyLedgerToPosition(
                userId,
                portfolioSymbol,
                strategyKey,
                executionRepository.findAllForUserAndSymbolsOrdered(userId, variants),
                false);
        deleteDuplicatePositionRows(userId, portfolioSymbol);
    }

    /**
     * Clears a stale OMS open leg when broker truth confirms the account is flat for the symbol.
     */
    @Transactional
    public void clearStaleOpenPosition(UUID userId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String trimmed = symbol.trim();
        Optional<PortfolioPosition> direct = positionRepository.findByUserIdAndSymbolAndDeletedFalse(userId, trimmed);
        Optional<PortfolioPosition> prefixed = trimmed.contains(":")
                ? Optional.empty()
                : positionRepository.findByUserIdAndSymbolAndDeletedFalse(userId, "NSE:" + trimmed);
        PortfolioPosition pos = direct.or(() -> prefixed).orElse(null);
        if (pos == null) {
            return;
        }
        pos.setQuantity(BigDecimal.ZERO);
        pos.setAvgPrice(BigDecimal.ZERO);
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setMtmPrice(null);
        pos.setDeleted(true);
        positionRepository.save(pos);
    }

    /**
     * Clears OMS portfolio rows that show open qty but have no entry price (legacy paper/test ghosts).
     */
    @Transactional
    public int clearZeroPriceGhostPositions() {
        int cleared = 0;
        for (PortfolioPosition pos : positionRepository.findZeroPriceGhostPositions()) {
            log.info("portfolio.ghost_cleared user={} symbol={} strategy={} qty={}",
                    pos.getUserId(), pos.getSymbol(), pos.getStrategyKey(), pos.getQuantity());
            pos.setQuantity(BigDecimal.ZERO);
            pos.setAvgPrice(BigDecimal.ZERO);
            pos.setUnrealizedPnl(BigDecimal.ZERO);
            pos.setMtmPrice(null);
            pos.setDeleted(true);
            positionRepository.save(pos);
            cleared++;
        }
        return cleared;
    }

    private void applyLedgerToPosition(
            UUID userId,
            String symbol,
            String strategyKey,
            List<OmsExecution> executions,
            boolean liveLedgerOnly
    ) {
        Ledger ledger = new Ledger();
        for (OmsExecution e : executions) {
            if (e == null || e.getOrder() == null) {
                continue;
            }
            if (e.getFilledQty() == null || e.getAvgPrice() == null) {
                log.warn("portfolio.accounting.skip_invalid_execution user={} symbol={} executionId={} orderId={} filledQty={} avgPrice={}",
                        userId, symbol,
                        e.getId(),
                        e.getOrder() != null ? e.getOrder().getId() : null,
                        e.getFilledQty(),
                        e.getAvgPrice());
                continue;
            }
            String s = e.getOrder().getSide();
            ledger.apply(s, e.getFilledQty(), e.getAvgPrice());
        }

        PortfolioPosition pos = positionRepository
                .findByUserIdAndSymbolAndDeletedFalse(userId, symbol)
                .orElseGet(() -> {
                    PortfolioPosition p = new PortfolioPosition();
                    p.setUserId(userId);
                    p.setSymbol(symbol);
                    return p;
                });

        BigDecimal netQty = ledger.netQty();
        pos.setQuantity(netQty);
        pos.setAvgPrice(ledger.netAvgForOpenPosition());
        pos.setRealizedPnl(ledger.realized());
        pos.setUnrealizedPnl(BigDecimal.ZERO);
        pos.setMtmPrice(null);
        if (strategyKey != null) {
            pos.setStrategyKey(strategyKey);
        }
        if (liveLedgerOnly) {
            pos.setSimulation(false);
            pos.setSimulationRunId(null);
            pos.setSimulationScenario(null);
        } else if (!executions.isEmpty() && executions.get(0).getOrder().isSimulation()) {
            OmsOrder ref = executions.get(0).getOrder();
            pos.setSimulation(true);
            pos.setSimulationRunId(ref.getSimulationRunId());
            pos.setSimulationScenario(ref.getSimulationScenario());
        }
        if (netQty.compareTo(BigDecimal.ZERO) == 0 && liveLedgerOnly) {
            pos.setDeleted(true);
        }
        positionRepository.save(pos);
    }

    private Optional<PortfolioPosition> findActivePositionByNormalizedSymbol(UUID userId, String symbol) {
        String normalized = OmsSymbolNormalizer.normalize(symbol);
        return positionRepository.findByUserIdAndDeletedFalse(userId).stream()
                .filter(p -> normalized.equals(OmsSymbolNormalizer.normalize(p.getSymbol())))
                .findFirst();
    }

    private String resolvePortfolioSymbol(UUID userId, String symbol) {
        String normalized = OmsSymbolNormalizer.normalize(symbol);
        return positionRepository.findByUserIdAndDeletedFalse(userId).stream()
                .filter(p -> normalized.equals(OmsSymbolNormalizer.normalize(p.getSymbol())))
                .map(PortfolioPosition::getSymbol)
                .findFirst()
                .orElse(normalized);
    }

    private void deleteDuplicatePositionRows(UUID userId, String keeperSymbol) {
        String normalized = OmsSymbolNormalizer.normalize(keeperSymbol);
        for (PortfolioPosition position : positionRepository.findByUserIdAndDeletedFalse(userId)) {
            if (position.getSymbol() == null || position.getSymbol().equals(keeperSymbol)) {
                continue;
            }
            if (!normalized.equals(OmsSymbolNormalizer.normalize(position.getSymbol()))) {
                continue;
            }
            position.setQuantity(BigDecimal.ZERO);
            position.setAvgPrice(BigDecimal.ZERO);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setMtmPrice(null);
            position.setDeleted(true);
            positionRepository.save(position);
        }
    }

    private static List<String> symbolVariants(String symbol) {
        String normalized = OmsSymbolNormalizer.normalize(symbol);
        Set<String> variants = new LinkedHashSet<>();
        if (symbol != null && !symbol.isBlank()) {
            variants.add(symbol.trim().toUpperCase());
        }
        variants.add(normalized);
        if (normalized.startsWith("NSE:")) {
            variants.add(OmsSymbolNormalizer.display(normalized));
        }
        return List.copyOf(variants);
    }

    private static final class Ledger {
        private BigDecimal longQty = BigDecimal.ZERO;
        private BigDecimal longAvg = BigDecimal.ZERO;
        private BigDecimal shortQty = BigDecimal.ZERO;
        private BigDecimal shortAvg = BigDecimal.ZERO;
        private BigDecimal realized = BigDecimal.ZERO;

        void apply(String side, BigDecimal qty, BigDecimal price) {
            if ("BUY".equalsIgnoreCase(side)) {
                buy(qty, price);
            } else {
                sell(qty, price);
            }
        }

        private void buy(BigDecimal q, BigDecimal p) {
            if (q == null || p == null) {
                return;
            }
            BigDecimal remaining = q;

            if (shortQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cover = remaining.min(shortQty);
                realized = realized.add(shortAvg.subtract(p).multiply(cover));
                shortQty = shortQty.subtract(cover);
                remaining = remaining.subtract(cover);
                if (shortQty.compareTo(BigDecimal.ZERO) == 0) {
                    shortAvg = BigDecimal.ZERO;
                }
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newLongQty = longQty.add(remaining);
                BigDecimal cost = longQty.multiply(longAvg).add(remaining.multiply(p));
                longAvg = newLongQty.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : cost.divide(newLongQty, 8, RoundingMode.HALF_UP);
                longQty = newLongQty;
            }
        }

        private void sell(BigDecimal q, BigDecimal p) {
            if (q == null || p == null) {
                return;
            }
            BigDecimal remaining = q;

            if (longQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal close = remaining.min(longQty);
                realized = realized.add(p.subtract(longAvg).multiply(close));
                longQty = longQty.subtract(close);
                remaining = remaining.subtract(close);
                if (longQty.compareTo(BigDecimal.ZERO) == 0) {
                    longAvg = BigDecimal.ZERO;
                }
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newShortQty = shortQty.add(remaining);
                BigDecimal proceeds = shortQty.multiply(shortAvg).add(remaining.multiply(p));
                shortAvg = newShortQty.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : proceeds.divide(newShortQty, 8, RoundingMode.HALF_UP);
                shortQty = newShortQty;
            }
        }

        BigDecimal realized() {
            return realized.setScale(8, RoundingMode.HALF_UP);
        }

        BigDecimal netQty() {
            return longQty.subtract(shortQty).setScale(8, RoundingMode.HALF_UP);
        }

        BigDecimal netAvgForOpenPosition() {
            if (longQty.compareTo(BigDecimal.ZERO) > 0) {
                return longAvg;
            }
            if (shortQty.compareTo(BigDecimal.ZERO) > 0) {
                return shortAvg;
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * Rebuild every symbol touched for a user (admin/regeneration).
     */
    @Transactional
    public void rebuildAllSymbols(UUID userId) {
        List<OmsExecution> trades = executionRepository.findAllForUserOrdered(userId);
        Map<String, Boolean> seen = new HashMap<>();
        for (OmsExecution t : trades) {
            seen.put(t.getOrder().getSymbol(), Boolean.TRUE);
        }
        for (String sym : seen.keySet()) {
            rebuildSymbol(userId, sym);
        }
        recordSnapshot(userId, ZoneId.of("Asia/Kolkata"));
    }

    @Transactional
    public PortfolioPnlSnapshot recordSnapshot(UUID userId, ZoneId zoneId) {
        List<PortfolioPosition> positions = positionRepository.findByUserIdAndDeletedFalse(userId);
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (PortfolioPosition p : positions) {
            realized = realized.add(nullSafe(p.getRealizedPnl()));
            unrealized = unrealized.add(nullSafe(p.getUnrealizedPnl()));
        }
        BigDecimal mtm = realized.add(unrealized).setScale(8, RoundingMode.HALF_UP);

        PortfolioPnlSnapshot snapshot = new PortfolioPnlSnapshot();
        snapshot.setUserId(userId);
        snapshot.setAsOf(Instant.now());
        snapshot.setRealizedPnl(realized.setScale(8, RoundingMode.HALF_UP));
        snapshot.setUnrealizedPnl(unrealized.setScale(8, RoundingMode.HALF_UP));
        snapshot.setMtmPnl(mtm);
        snapshot.setPnl(mtm);
        snapshot = pnlSnapshotRepository.save(snapshot);

        LocalDate businessDate = snapshot.getAsOf().atZone(zoneId).toLocalDate();
        PortfolioDailySummary daily = dailySummaryRepository.findByUserIdAndBusinessDateAndDeletedFalse(userId, businessDate)
                .orElseGet(() -> {
                    PortfolioDailySummary s = new PortfolioDailySummary();
                    s.setUserId(userId);
                    s.setBusinessDate(businessDate);
                    return s;
                });
        daily.setRealizedPnl(snapshot.getRealizedPnl());
        daily.setUnrealizedPnl(snapshot.getUnrealizedPnl());
        daily.setMtmPnl(snapshot.getMtmPnl());
        daily.setCumulativePnl(snapshot.getPnl());
        dailySummaryRepository.save(daily);
        eventPublisher.publishEvent(new RealtimeBridgeEvents.PnlUpdated(userId, snapshot.getAsOf()));
        return snapshot;
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
