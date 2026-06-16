package com.stokr.execution.pipeline;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.execution.comparison.TradeLifecycleReconciliationService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalLifecycleService;
import com.stokr.strategy.service.SignalPriceEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ensures linked strategy signals carry entry/stop/target after a broker entry fill so
 * {@link com.stokr.strategy.service.SignalOutcomeTrackerService} can detect TP/SL hits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalBrokerFillEnrichmentService {

    private static final Set<OrderState> ENTRY_FILLED_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED
    );

    private static final Set<String> FILL_TOPICS = Set.of("broker_fill_synced", "execution_fill_complete");

    private final OmsOrderRepository omsOrderRepository;
    private final OmsTradeRepository omsTradeRepository;
    private final StrategySignalRepository signalRepository;
    private final SignalPriceEnrichmentService signalPriceEnrichmentService;

    @EventListener
    @Transactional
    public void onFillEvent(OperationalRealtimeEvent event) {
        if (event == null || event.topic() == null || !FILL_TOPICS.contains(event.topic())) {
            return;
        }
        var payload = event.payload();
        if (payload == null) {
            return;
        }
        UUID orderId = parseUuid(payload.get("orderId"));
        if (orderId == null) {
            return;
        }
        OmsOrder order = omsOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.isDeleted() || order.getSignalId() == null) {
            return;
        }
        if (TradeLifecycleReconciliationService.isExitOrder(order)) {
            return;
        }
        if (!ENTRY_FILLED_STATES.contains(order.getState())) {
            return;
        }
        enrichLinkedSignal(order);
    }

    private void enrichLinkedSignal(OmsOrder order) {
        StrategySignalEntity signal = signalRepository.findById(order.getSignalId()).orElse(null);
        if (signal == null || signal.isDeleted() || Boolean.TRUE.equals(signal.getTestTrade())) {
            return;
        }
        Instant asOf = order.getCreatedAt() != null ? order.getCreatedAt() : Instant.now();
        BigDecimal fillPrice = resolveFillPrice(order);
        signalPriceEnrichmentService.enrichOnEntryFill(signal, fillPrice, asOf);
        if (signal.getOutcomeStatus() == null
                || signal.getOutcomeStatus().isBlank()
                || "PENDING".equals(signal.getOutcomeStatus())) {
            SignalLifecycleService.updateOutcome(signal, "RUNNING");
        }
        signalRepository.save(signal);
        log.info("signal.fill_enriched signalId={} orderId={} symbol={} entry={} stop={} target={}",
                signal.getId(), order.getId(), signal.getSymbol(),
                signal.getEntryReferencePrice(), signal.getStopPrice(), signal.getTargetPrice());
    }

    private BigDecimal resolveFillPrice(OmsOrder order) {
        if (order.getEntryReferencePrice() != null && order.getEntryReferencePrice().signum() > 0) {
            return order.getEntryReferencePrice();
        }
        List<com.stokr.oms.domain.OmsTrade> trades = omsTradeRepository.findByOrder_IdOrderByCreatedAtAsc(order.getId());
        for (int i = trades.size() - 1; i >= 0; i--) {
            BigDecimal px = trades.get(i).getPrice();
            if (px != null && px.signum() > 0) {
                return px;
            }
        }
        return null;
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
