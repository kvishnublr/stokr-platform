package com.stokr.admin.signal;

import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.trace.ExecutionTimelineProjection;
import com.stokr.oms.trace.ExecutionTraceEvent;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminSignalQueryService {

    private final StrategySignalRepository signalRepo;
    private final OmsOrderRepository orderRepo;
    private final ExecutionTimelineProjection timelineProjection;

    public Page<AdminSignalDto> pageSignals(AdminSignalParams p, Pageable pageable) {
        Specification<StrategySignalEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            predicates.add(cb.isNull(root.get("backtestRunId")));
            if (!Boolean.TRUE.equals(p.includeTestTrades())) {
                predicates.add(cb.isFalse(root.get("testTrade")));
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
                predicates.add(cb.equal(cb.upper(root.get("outcomeStatus")),
                        p.outcomeStatus().trim().toUpperCase()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return signalRepo.findAll(spec, pageable).map(this::toDto);
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
            return new AdminSignalStatsDto(0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0);
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
                toLong(r[10]) // totalAllTime
        );
    }

    private AdminSignalDto toDto(StrategySignalEntity s) {
        return new AdminSignalDto(
                s.getId(),
                s.getStrategyName(),
                s.getSymbol(),
                s.getSignalType() != null ? s.getSignalType().name() : null,
                s.getPipeline(),
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
                s.getExitPrice()
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

    private static long toLong(Object v) {
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        return ((Number) v).doubleValue();
    }

    private static Instant startOfToday() {
        return Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
    }
}
