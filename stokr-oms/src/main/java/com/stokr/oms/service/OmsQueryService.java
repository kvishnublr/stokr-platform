package com.stokr.oms.service;

import com.stokr.common.exception.ForbiddenException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderDetailDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.OmsSummaryMetricsDto;
import com.stokr.oms.dto.OmsTradeRowDto;
import com.stokr.oms.metrics.ExecutionMetricsHelper;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.query.PipelineMode;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.spec.OmsOrderSpecifications;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OmsQueryService {

    private static final Instant METRICS_FROM_SENTINEL = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant METRICS_TO_SENTINEL = Instant.parse("2099-01-01T00:00:00Z");

    private final OmsOrderRepository orderRepository;
    private final OmsExecutionRepository executionRepository;
    private final OmsTradeRepository tradeRepository;

    public Page<OmsOrderSummaryDto> pageOrders(UUID restrictUserId, OmsReadParams p, Pageable pageable) {
        return orderRepository.findAll(orderSpec(restrictUserId, p), pageable).map(this::toOrderSummary);
    }

    public Page<OmsExecutionRowDto> pageExecutions(UUID restrictUserId, OmsReadParams p, Pageable pageable) {
        return executionRepository.findAll(executionSpec(restrictUserId, p), pageable).map(this::toExecutionRow);
    }

    public Page<OmsTradeRowDto> pageTrades(UUID restrictUserId, OmsReadParams p, Pageable pageable) {
        return tradeRepository.findAll(tradeSpec(restrictUserId, p), pageable).map(this::toTradeRow);
    }

    public OmsOrderDetailDto orderDetail(UUID principalUserId, UUID orderId, boolean principalIsAdmin) {
        OmsOrder o = orderRepository.findById(orderId).filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!principalIsAdmin && !o.getUserId().equals(principalUserId)) {
            throw new ForbiddenException("Forbidden");
        }
        List<OmsExecutionRowDto> execs = executionRepository.findByOrder_IdOrderByCreatedAtAsc(orderId).stream()
                .filter(e -> !e.isDeleted())
                .map(this::toExecutionRow)
                .toList();
        List<OmsTradeRowDto> trades = tradeRepository.findByOrder_IdOrderByCreatedAtAsc(orderId).stream()
                .filter(t -> !t.isDeleted())
                .map(this::toTradeRow)
                .toList();
        return new OmsOrderDetailDto(toOrderSummary(o), execs, trades);
    }

    public OmsSummaryMetricsDto summarize(UUID restrictUserId, OmsReadParams p) {
        Specification<OmsOrder> base = orderSpec(restrictUserId, p);
        long total = orderRepository.count(base);
        long rejects = orderRepository.count(Specification.where(base).and(OmsOrderSpecifications.stateEquals(OrderState.REJECTED)));
        long cancelled = orderRepository.count(Specification.where(base).and(OmsOrderSpecifications.stateEquals(OrderState.CANCELLED)));
        Specification<OmsExecution> execBase = executionSpec(restrictUserId, p);
        long fillLegs = executionRepository.count(Specification.where(execBase).and(filledLegSpec()));
        Instant from = p.fromInclusive() != null ? p.fromInclusive() : METRICS_FROM_SENTINEL;
        Instant to = p.toExclusive() != null ? p.toExclusive() : METRICS_TO_SENTINEL;
        Double avgLat = executionRepository.averageLatencyMs(restrictUserId, from, to);
        BigDecimal avgSlip = executionRepository.averageSlippageBps(restrictUserId, from, to);
        return new OmsSummaryMetricsDto(total, rejects, cancelled, fillLegs, avgLat, avgSlip);
    }

    private Specification<OmsExecution> filledLegSpec() {
        return (root, q, cb) -> cb.gt(root.get("filledQty"), BigDecimal.ZERO);
    }

    private Specification<OmsOrder> orderSpec(UUID restrictUserId, OmsReadParams p) {
        return Specification.where(OmsOrderSpecifications.notDeleted())
                .and(OmsOrderSpecifications.notTestTrade())
                .and(OmsOrderSpecifications.userId(restrictUserId))
                .and(OmsOrderSpecifications.symbolEquals(p.symbol()))
                .and(OmsOrderSpecifications.strategyKeyEquals(p.strategyKey()))
                .and(OmsOrderSpecifications.brokerVendorEquals(p.brokerVendor()))
                .and(OmsOrderSpecifications.stateEquals(p.state()))
                .and(OmsOrderSpecifications.executionModeEquals(p.executionMode()))
                .and(OmsOrderSpecifications.pipelineMode(p.pipelineMode()))
                .and(OmsOrderSpecifications.createdBetween(p.fromInclusive(), p.toExclusive()));
    }

    private Specification<OmsExecution> executionSpec(UUID restrictUserId, OmsReadParams p) {
        return (root, query, cb) -> {
            Join<OmsExecution, OmsOrder> ord = root.join("order");
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isFalse(root.get("deleted")));
            preds.add(cb.isFalse(ord.get("deleted")));
            preds.add(cb.isFalse(ord.get("testTrade")));
            if (restrictUserId != null) {
                preds.add(cb.equal(ord.get("userId"), restrictUserId));
            }
            if (p.symbol() != null && !p.symbol().isBlank()) {
                preds.add(cb.equal(ord.get("symbol"), p.symbol().trim()));
            }
            if (p.strategyKey() != null && !p.strategyKey().isBlank()) {
                preds.add(cb.equal(ord.get("strategyKey"), p.strategyKey().trim()));
            }
            if (p.brokerVendor() != null && !p.brokerVendor().isBlank()) {
                preds.add(cb.equal(ord.get("brokerVendor"), p.brokerVendor().trim()));
            }
            if (p.state() != null) {
                preds.add(cb.equal(ord.get("state"), p.state()));
            }
            if (p.executionMode() != null) {
                preds.add(cb.equal(ord.get("executionMode"), p.executionMode()));
            }
            if (p.pipelineMode() != null && p.pipelineMode() != PipelineMode.ALL) {
                if (p.pipelineMode() == PipelineMode.LIVE) {
                    preds.add(cb.isNull(ord.get("backtestRunId")));
                } else {
                    preds.add(cb.isNotNull(ord.get("backtestRunId")));
                }
            }
            if (p.fromInclusive() != null || p.toExclusive() != null) {
                Path<Instant> ts = root.get("executionTimestamp");
                Path<Instant> fill = root.get("fillTime");
                Path<Instant> created = root.get("createdAt");
                Expression<Instant> coalesced = cb.coalesce(cb.coalesce(ts, fill), created);
                if (p.fromInclusive() != null) {
                    preds.add(cb.greaterThanOrEqualTo(coalesced, p.fromInclusive()));
                }
                if (p.toExclusive() != null) {
                    preds.add(cb.lessThan(coalesced, p.toExclusive()));
                }
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private Specification<OmsTrade> tradeSpec(UUID restrictUserId, OmsReadParams p) {
        return (root, query, cb) -> {
            Join<OmsTrade, OmsOrder> ord = root.join("order");
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isFalse(root.get("deleted")));
            preds.add(cb.isFalse(ord.get("deleted")));
            preds.add(cb.isFalse(ord.get("testTrade")));
            if (restrictUserId != null) {
                preds.add(cb.equal(ord.get("userId"), restrictUserId));
            }
            if (p.symbol() != null && !p.symbol().isBlank()) {
                preds.add(cb.equal(ord.get("symbol"), p.symbol().trim()));
            }
            if (p.strategyKey() != null && !p.strategyKey().isBlank()) {
                preds.add(cb.equal(ord.get("strategyKey"), p.strategyKey().trim()));
            }
            if (p.brokerVendor() != null && !p.brokerVendor().isBlank()) {
                preds.add(cb.equal(ord.get("brokerVendor"), p.brokerVendor().trim()));
            }
            if (p.state() != null) {
                preds.add(cb.equal(ord.get("state"), p.state()));
            }
            if (p.executionMode() != null) {
                preds.add(cb.equal(ord.get("executionMode"), p.executionMode()));
            }
            if (p.pipelineMode() != null && p.pipelineMode() != PipelineMode.ALL) {
                if (p.pipelineMode() == PipelineMode.LIVE) {
                    preds.add(cb.isNull(ord.get("backtestRunId")));
                } else {
                    preds.add(cb.isNotNull(ord.get("backtestRunId")));
                }
            }
            if (p.fromInclusive() != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), p.fromInclusive()));
            }
            if (p.toExclusive() != null) {
                preds.add(cb.lessThan(root.get("createdAt"), p.toExclusive()));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private OmsOrderSummaryDto toOrderSummary(OmsOrder o) {
        return new OmsOrderSummaryDto(
                o.getId(),
                o.getUserId(),
                o.getCorrelationId(),
                o.getSignalId(),
                o.getStrategyKey(),
                o.getExecutionMode() != null ? o.getExecutionMode().name() : null,
                o.getSymbol(),
                o.getSide(),
                o.getOrderType(),
                o.getQuantity(),
                o.getLimitPrice(),
                o.getBacktestRunId(),
                o.getState().name(),
                o.getBrokerVendor(),
                o.getRejectReason(),
                o.getCreatedAt()
        );
    }

    private OmsExecutionRowDto toExecutionRow(OmsExecution e) {
        OmsOrder o = e.getOrder();
        BigDecimal referencePrice = ExecutionMetricsHelper.resolveReferencePrice(o, e);
        Long latencyMs = ExecutionMetricsHelper.resolveLatencyMs(o, e);
        BigDecimal slippageBps = ExecutionMetricsHelper.resolveSlippageBps(o, e);
        BigDecimal spreadBps = ExecutionMetricsHelper.resolveSpreadBps(e, BigDecimal.valueOf(8));
        return new OmsExecutionRowDto(
                e.getId(),
                o.getId(),
                o.getUserId(),
                o.getSymbol(),
                o.getStrategyKey(),
                o.getExecutionMode() != null ? o.getExecutionMode().name() : null,
                o.getState().name(),
                o.getBacktestRunId(),
                e.getBrokerExecutionId(),
                e.getExecutionSequence(),
                e.getFilledQty(),
                e.getAvgPrice(),
                e.getExecutionKind(),
                e.getFillTime(),
                e.getExecutionTimestamp(),
                latencyMs,
                slippageBps,
                spreadBps,
                referencePrice,
                e.getReplaySource(),
                e.getReplayRunId(),
                e.getCreatedAt()
        );
    }

    private OmsTradeRowDto toTradeRow(OmsTrade t) {
        OmsOrder o = t.getOrder();
        return new OmsTradeRowDto(
                t.getId(),
                o.getId(),
                t.getExecution().getId(),
                o.getUserId(),
                o.getSymbol(),
                o.getStrategyKey(),
                o.getExecutionMode() != null ? o.getExecutionMode().name() : null,
                o.getBacktestRunId(),
                t.getQuantity(),
                t.getPrice(),
                t.getCreatedAt()
        );
    }
}
