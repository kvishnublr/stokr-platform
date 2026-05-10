package com.stokr.execution.simulation;

import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import com.stokr.risk.service.RiskEventRecorder;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.service.ExecutionLedgerService;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionSimulator {

    private final OrderLifecycleService orderLifecycleService;
    private final MarketdataCandleRepository candleRepository;
    private final OmsTradeRepository tradeRepository;
    private final ExecutionLedgerService executionLedgerService;
    private final PortfolioAccountingService portfolioAccountingService;
    private final ApplicationEventPublisher eventPublisher;
    private final LiveTradingTraderEligibilityService liveTradingTraderEligibilityService;
    private final RiskEventRecorder riskEventRecorder;
    private final ObjectProvider<NotificationPublisher> notificationPublisher;

    @Value("${stokr.simulation.candle-timeframe:1m}")
    private String candleTimeframe;

    @Value("${stokr.simulation.latency-ms:1500}")
    private long latencyMs;

    @Value("${stokr.simulation.base-slippage-bps:1.0}")
    private BigDecimal baseSlippageBps;

    @Value("${stokr.simulation.base-spread-bps:1.0}")
    private BigDecimal baseSpreadBps;

    @Value("${stokr.simulation.partial-fill-count:1}")
    private int partialFillCount;

    @Value("${stokr.simulation.order-queue-delay-ms:0}")
    private long orderQueueDelayMs;

    @Transactional
    public void process(ExecutionDispatchMessage msg) {
        OmsOrder order = orderLifecycleService.getRequired(msg.orderId());
        if (order.getState() != OrderState.QUEUED) {
            log.info("execution.skip state={} orderId={}", order.getState(), order.getId());
            return;
        }

        if (order.getExecutionMode() == ExecutionMode.LIVE) {
            String vendor = msg.brokerVendor() != null ? msg.brokerVendor() : order.getBrokerVendor();
            LiveTraderEligibilityResult gate = liveTradingTraderEligibilityService.evaluateForLiveOrder(
                    order.getUserId(),
                    order.getStrategyKey(),
                    vendor != null ? vendor : "ZERODHA"
            );
            if (!gate.allowed()) {
                log.warn("execution.sim.live_blocked orderId={} reason={}", order.getId(), gate.reasonCode());
                riskEventRecorder.record(order.getUserId(), order.getId(), gate.reasonCode(), "REJECT", gate.message());
                notificationPublisher.ifAvailable(pub -> pub.publish(new NotificationEvent(
                        "IN_APP",
                        "TRADER_ELIGIBILITY_BLOCK",
                        order.getUserId(),
                        Map.of(
                                "reasonCode", gate.reasonCode() != null ? gate.reasonCode() : "",
                                "message", gate.message() != null ? gate.message() : ""
                        )
                )));
                orderLifecycleService.transition(order.getId(), OrderState.REJECTED, gate.message());
                return;
            }
            orderLifecycleService.submitToBroker(order, vendor != null ? vendor : "ZERODHA");
            return;
        }

        orderLifecycleService.transition(order.getId(), OrderState.SENT, null);
        orderLifecycleService.transition(order.getId(), OrderState.ACKNOWLEDGED, null);

        Instant fillAnchor = (order.getCreatedAt() != null ? order.getCreatedAt() : Instant.now()).plusMillis(orderQueueDelayMs);
        List<BigDecimal> fillLots = splitQuantity(order.getQuantity(), Math.max(1, partialFillCount));
        BigDecimal lastFillPrice = null;
        for (int i = 0; i < fillLots.size(); i++) {
            MarketdataCandle fillCandle = selectFillCandle(order.getSymbol(), fillAnchor.plusMillis((long) i * latencyMs));
            BigDecimal ref = fillCandle != null ? fillCandle.getClosePrice() : safePrice(order);
            BigDecimal fillPrice = applyExecutionCosts(ref, order.getSide());
            lastFillPrice = fillPrice;
            Instant ts = fillCandle != null ? fillCandle.getOpenTime().plusMillis(latencyMs) : Instant.now().plusMillis(latencyMs);

            var ex = executionLedgerService.appendExecution(
                    order,
                    "sim-" + order.getId() + "-" + (i + 1),
                    fillLots.get(i),
                    fillPrice,
                    "SIM",
                    ts,
                    latencyMs,
                    baseSlippageBps,
                    baseSpreadBps,
                    ref,
                    "SIMULATED",
                    order.getBacktestRunId()
            );

            OmsTrade tr = new OmsTrade();
            tr.setOrder(order);
            tr.setExecution(ex);
            tr.setQuantity(fillLots.get(i));
            tr.setPrice(fillPrice);
            tradeRepository.save(tr);
        }

        orderLifecycleService.transition(order.getId(), OrderState.FILLED, null);
        portfolioAccountingService.applyFill(order.getUserId(), order.getSymbol());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.OrderUpdate(
                order.getUserId(),
                order.getId(),
                order.getSymbol(),
                OrderState.FILLED.name(),
                Instant.now()
        ));
        log.info("execution.simulated orderId={} lastFillPrice={} fills={}", order.getId(), lastFillPrice, fillLots.size());
    }

    private MarketdataCandle selectFillCandle(String symbol, Instant anchor) {
        long stepMs = timeframeMillis(candleTimeframe);
        long buckets = Math.max(1, (latencyMs + stepMs - 1) / stepMs);
        Instant target = anchor.plusMillis(buckets * stepMs);

        List<MarketdataCandle> candles = candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                symbol,
                candleTimeframe,
                target.minusSeconds(60),
                target.plusSeconds(120)
        );
        MarketdataCandle best = null;
        for (MarketdataCandle c : candles) {
            if (!c.getOpenTime().isBefore(target)) {
                best = c;
                break;
            }
            best = c;
        }
        return best;
    }

    private static long timeframeMillis(String tf) {
        if (tf != null && tf.endsWith("m")) {
            long mins = Long.parseLong(tf.substring(0, tf.length() - 1));
            return mins * 60_000L;
        }
        return 60_000L;
    }

    private BigDecimal applyExecutionCosts(BigDecimal referencePrice, String side) {
        BigDecimal slip = referencePrice.multiply(baseSlippageBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP);
        BigDecimal halfSpread = referencePrice.multiply(baseSpreadBps).divide(BigDecimal.valueOf(20_000), 8, RoundingMode.HALF_UP);
        if ("BUY".equalsIgnoreCase(side)) {
            return referencePrice.add(slip).add(halfSpread).setScale(8, RoundingMode.HALF_UP);
        }
        return referencePrice.subtract(slip).subtract(halfSpread).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal safePrice(OmsOrder order) {
        if (order.getEntryReferencePrice() != null) {
            return order.getEntryReferencePrice();
        }
        if (order.getLimitPrice() != null) {
            return order.getLimitPrice();
        }
        return BigDecimal.ZERO;
    }

    private static List<BigDecimal> splitQuantity(BigDecimal quantity, int slices) {
        if (slices <= 1 || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of(quantity);
        }
        List<BigDecimal> out = new ArrayList<>(slices);
        BigDecimal base = quantity.divide(BigDecimal.valueOf(slices), 8, RoundingMode.DOWN);
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < slices - 1; i++) {
            out.add(base);
            acc = acc.add(base);
        }
        out.add(quantity.subtract(acc));
        return out;
    }
}
