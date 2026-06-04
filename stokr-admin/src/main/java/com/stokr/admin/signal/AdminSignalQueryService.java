package com.stokr.admin.signal;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.trace.ExecutionTimelineProjection;
import com.stokr.oms.trace.ExecutionTraceEvent;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminSignalQueryService {

    private final StrategySignalRepository signalRepo;
    private final OmsOrderRepository orderRepo;
    private final ExecutionTimelineProjection timelineProjection;
    private final MarketDataQueryService marketDataQueryService;
    private final StrategyExecutionConfigService executionConfigService;

    public Page<AdminSignalDto> pageSignals(AdminSignalParams p, Pageable pageable) {
        Specification<StrategySignalEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            predicates.add(cb.isNull(root.get("backtestRunId")));
            if (!Boolean.TRUE.equals(p.includeTestTrades())) {
                predicates.add(cb.isFalse(root.get("testTrade")));
            }
            if (!Boolean.TRUE.equals(p.includeReplayAndLab())) {
                predicates.add(cb.or(
                        cb.isNull(root.get("signalSource")),
                        root.get("signalSource").in(SignalProvenance.LIVE, SignalProvenance.PAPER)
                ));
            }
            if (p.strategyName() != null && !p.strategyName().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("strategyName")),
                        "%" + p.strategyName().trim().toLowerCase() + "%"));
            }
            if (p.symbol() != null && !p.symbol().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("symbol")),
                        "%" + p.symbol().trim().toLowerCase() + "%"));
            }
            if (p.signalType() != null && !p.signalType().isBlank()
                    && !"ALL".equalsIgnoreCase(p.signalType())) {
                predicates.add(cb.equal(cb.upper(root.get("signalType").as(String.class)),
                        p.signalType().trim().toUpperCase()));
            }
            if (p.pipeline() != null && !p.pipeline().isBlank()
                    && !"ALL".equalsIgnoreCase(p.pipeline())) {
                predicates.add(cb.equal(cb.upper(root.get("pipeline")),
                        p.pipeline().trim().toUpperCase()));
            }
            if (p.userId() != null) {
                predicates.add(cb.equal(root.get("userId"), p.userId()));
            }
            if (p.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), p.from()));
            }
            if (p.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), p.to()));
            }
            if (p.outcomeStatus() != null && !p.outcomeStatus().isBlank()
                    && !"ALL".equalsIgnoreCase(p.outcomeStatus())) {
                addOutcomePredicate(predicates, cb, root, p.outcomeStatus().trim());
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<StrategySignalEntity> page = signalRepo.findAll(spec, pageable);

        // Batch LTP lookup for all unique symbols
        Map<String, BigDecimal> ltpCache = new HashMap<>();
        for (StrategySignalEntity s : page.getContent()) {
            if (s.getSymbol() != null) ltpCache.putIfAbsent(s.getSymbol(), null);
        }
        for (String sym : ltpCache.keySet()) {
            try {
                ltpCache.put(sym, lastPrice(sym));
            } catch (Exception e) {
                ltpCache.put(sym, BigDecimal.ZERO);
            }
        }

        return page.map(s -> toDtoWithLtp(s, ltpCache.getOrDefault(s.getSymbol(), BigDecimal.ZERO)));
    }

    public AdminSignalDetailDto detail(UUID id) {
        StrategySignalEntity sig = signalRepo.findById(id)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("Signal not found: " + id));

        List<OmsOrder> orders = orderRepo.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(sig.getId());
        List<OmsOrderSummaryDto> orderDtos = orders.stream().map(this::toOrderSummary).toList();

        List<ExecutionTraceEvent> timeline = orders.isEmpty()
                ? List.of()
                : timelineProjection.timelineForOrder(orders.get(0).getId());

        return new AdminSignalDetailDto(toDto(sig), orderDtos, timeline);
    }

    public AdminSignalStatsDto stats(Instant since) {
        List<Object[]> rows = signalRepo.computeStats(since);
        if (rows.isEmpty()) {
            return new AdminSignalStatsDto(0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0);
        }
        Object[] r = rows.get(0);
        return new AdminSignalStatsDto(
                toLong(r[0]),  // totalToday
                toLong(r[1]),  // buyToday
                toLong(r[2]),  // sellToday
                toLong(r[3]),  // liveToday
                toLong(r[4]),  // paperToday
                toDouble(r[5]), // avgConfidence
                toLong(r[6]),  // targetHit
                toLong(r[7]),  // slHit
                toLong(r[8]),  // running
                toLong(r[9]),  // expired
                toLong(r[10]), // protectedExit
                toLong(r[11]), // pending
                toLong(r[12])  // totalAllTime
        );
    }

    private AdminSignalDto toDto(StrategySignalEntity s) {
        return toDtoWithLtp(s, null);
    }

    private AdminSignalDto toDtoWithLtp(StrategySignalEntity s, BigDecimal ltp) {
        // Compute live P&L
        BigDecimal pnl = null;
        boolean isClosed = s.getOutcomeStatus() != null && (
                "TARGET_HIT".equalsIgnoreCase(s.getOutcomeStatus())
             || "STOPLOSS_HIT".equalsIgnoreCase(s.getOutcomeStatus())
             || "SL_HIT".equalsIgnoreCase(s.getOutcomeStatus())
             || "BREAKEVEN_EXIT".equalsIgnoreCase(s.getOutcomeStatus())
             || "PRESSURE_EXIT".equalsIgnoreCase(s.getOutcomeStatus())
             || "EXPIRED".equalsIgnoreCase(s.getOutcomeStatus())
             || "CLOSED".equalsIgnoreCase(s.getOutcomeStatus()));

        if (isClosed && s.getRealizedPnl() != null) {
            pnl = s.getRealizedPnl();
        } else if (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0
                && s.getEntryReferencePrice() != null
                && s.getEntryReferencePrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal qty = s.getSuggestedQty() != null ? s.getSuggestedQty() : BigDecimal.ONE;
            boolean isBuy = s.getSignalType() != null && "BUY".equalsIgnoreCase(s.getSignalType().name());
            pnl = isBuy
                    ? ltp.subtract(s.getEntryReferencePrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP)
                    : s.getEntryReferencePrice().subtract(ltp).multiply(qty).setScale(2, RoundingMode.HALF_UP);
        }

        return new AdminSignalDto(
                s.getId(),
                s.getStrategyName(),
                s.getSymbol(),
                s.getSignalType() != null ? s.getSignalType().name() : null,
                resolveDisplayPipeline(s),
                s.getSignalSource() != null ? s.getSignalSource().name() : null,
                s.getConfidenceScore(),
                s.getEntryReferencePrice(),
                s.getStopPrice(),
                s.getTargetPrice(),
                s.getSuggestedQty(),
                s.getReason(),
                s.getMarketRegime(),
                s.getUserId(),
                s.getCreatedAt(),
                s.getOutcomeStatus(),
                s.getRealizedPnl(),
                s.getUnrealizedPnl(),
                s.getMaxFavorableExcursion(),
                s.getMaxAdverseExcursion(),
                s.getHitTarget(),
                s.getHitStoploss(),
                s.getRiskRewardAchieved(),
                s.getExecutionLatencyMs(),
                s.getEntryPrice(),
                s.getExitPrice(),
                ltp,
                pnl
        );
    }

    private OmsOrderSummaryDto toOrderSummary(OmsOrder o) {
        return new OmsOrderSummaryDto(
                o.getId(), o.getUserId(), o.getCorrelationId(), o.getSignalId(),
                o.getStrategyKey(), o.getExecutionMode() != null ? o.getExecutionMode().name() : null,
                o.getSymbol(), o.getSide(), o.getOrderType(), o.getQuantity(), o.getLimitPrice(),
                o.getBacktestRunId(), o.getState() != null ? o.getState().name() : null,
                o.getBrokerVendor(), o.getRejectReason(), o.getCreatedAt()
        );
    }

    private BigDecimal lastPrice(String symbol) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "1m", 1);
        if (bars.isEmpty() || bars.getFirst().getClosePrice() == null) {
            return BigDecimal.ZERO;
        }
        return bars.getFirst().getClosePrice();
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        return ((Number) v).doubleValue();
    }

    /**
     * Maps UI outcome filters to DB values. New signals start as PENDING/null; the tracker
     * promotes them to RUNNING or terminal states (TARGET_HIT, STOPLOSS_HIT, EXPIRED, …).
     */
    private static void addOutcomePredicate(
            List<Predicate> predicates,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Root<StrategySignalEntity> root,
            String outcomeFilter) {
        String normalized = outcomeFilter.toUpperCase();
        switch (normalized) {
            case "RUNNING", "ACTIVE", "OPEN", "PENDING" -> predicates.add(cb.or(
                    cb.isNull(root.get("outcomeStatus")),
                    cb.equal(cb.upper(root.get("outcomeStatus")), "PENDING"),
                    cb.equal(cb.upper(root.get("outcomeStatus")), "RUNNING")
            ));
            case "STOPLOSS_HIT", "SL_HIT" -> predicates.add(cb.or(
                    cb.equal(cb.upper(root.get("outcomeStatus")), "STOPLOSS_HIT"),
                    cb.equal(cb.upper(root.get("outcomeStatus")), "SL_HIT")
            ));
            default -> predicates.add(cb.equal(cb.upper(root.get("outcomeStatus")), normalized));
        }
    }

    private static Instant startOfToday() {
        return Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    /**
     * Legacy rows stored pipeline=PAPER while admin config was BOTH; prefer config and OMS legs for display.
     */
    private String resolveDisplayPipeline(StrategySignalEntity s) {
        String stored = s.getPipeline();
        if (stored != null && ("BOTH".equalsIgnoreCase(stored) || "LIVE".equalsIgnoreCase(stored))) {
            return stored.toUpperCase();
        }
        boolean liveLeg = false;
        boolean paperLeg = false;
        for (OmsOrder o : orderRepo.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(s.getId())) {
            if (o.getExecutionMode() == ExecutionMode.LIVE) {
                liveLeg = true;
            } else if (o.getExecutionMode() == ExecutionMode.PAPER || o.getExecutionMode() == ExecutionMode.SIMULATED) {
                paperLeg = true;
            }
        }
        if (liveLeg && paperLeg) {
            return "BOTH";
        }
        if (liveLeg) {
            return "LIVE";
        }
        String configured = executionConfigService.getByStrategyKey(s.getStrategyName())
                .map(c -> c.getExecutionMode())
                .map(m -> m == null ? null : m.trim().toUpperCase())
                .orElse(null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return stored != null ? stored : "PAPER";
    }
}
