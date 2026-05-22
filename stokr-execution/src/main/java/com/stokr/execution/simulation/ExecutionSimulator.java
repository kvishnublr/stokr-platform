package com.stokr.execution.simulation;

import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.common.execution.DeterministicExecutionRng;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import com.stokr.risk.service.RiskEventRecorder;
import com.stokr.oms.trace.ExecutionTraceService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.service.ExecutionLedgerService;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.execution.guard.ExecutionGuardTelemetryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionSimulator {

    private static final java.math.MathContext MC = new java.math.MathContext(12, RoundingMode.HALF_UP);

    private final OrderLifecycleService orderLifecycleService;
    private final MarketdataCandleRepository candleRepository;
    private final OmsTradeRepository tradeRepository;
    private final ExecutionLedgerService executionLedgerService;
    private final PortfolioAccountingService portfolioAccountingService;
    private final ApplicationEventPublisher eventPublisher;
    private final LiveTradingTraderEligibilityService liveTradingTraderEligibilityService;
    private final RiskEventRecorder riskEventRecorder;
    private final ObjectProvider<NotificationPublisher> notificationPublisher;
    private final ExecutionTraceService executionTraceService;
    private final StrategySignalRepository strategySignalRepository;
    private final ExecutionGuardTelemetryService executionGuardTelemetryService;
    private final BrokerAccountRepository brokerAccountRepository;
    private final FieldCipher fieldCipher;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;

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
        if (order.getState() != OrderState.PENDING_SUBMISSION) {
            log.info("execution.skip state={} orderId={}", order.getState(), order.getId());
            return;
        }

        if (order.getExecutionMode() == ExecutionMode.LIVE) {
            String vendor = msg.brokerVendor() != null ? msg.brokerVendor() : order.getBrokerVendor();
            final UUID liveUserId = order.getUserId();
            LiveTraderEligibilityResult gate = liveTradingTraderEligibilityService.evaluateForLiveOrder(
                    liveUserId,
                    order.getStrategyKey(),
                    vendor != null ? vendor : "ZERODHA"
            );
            if (!gate.allowed()) {
                log.warn("execution.sim.live_blocked orderId={} reason={}", order.getId(), gate.reasonCode());
                riskEventRecorder.record(liveUserId, order.getId(), gate.reasonCode(), "REJECT", gate.message());
                notificationPublisher.ifAvailable(pub -> pub.publish(new NotificationEvent(
                        "IN_APP",
                        "TRADER_ELIGIBILITY_BLOCK",
                        liveUserId,
                        new LinkedHashMap<>(Map.of(
                                "reasonCode", gate.reasonCode() != null ? gate.reasonCode() : "",
                                "message", gate.message() != null ? gate.message() : ""
                        ))
                )));
                orderLifecycleService.transition(order.getId(), OrderState.REJECTED, gate.message());
                executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                        "phase", "LIVE_GATE",
                        "reason", gate.message() != null ? gate.message() : ""
                ));
                return;
            }
            String effectiveVendor = vendor != null ? vendor : "ZERODHA";
            String[] creds = resolveBrokerCredentials(liveUserId, effectiveVendor);
            orderLifecycleService.submitToBroker(order, effectiveVendor, creds[0], creds[1]);
            executionTraceService.trace(order, ExecutionEventType.BROKER_SUBMITTED, Map.of(
                    "brokerVendor", effectiveVendor
            ));
            executionTraceService.trace(order, ExecutionEventType.ORDER_ACCEPTED, Map.of(
                    "brokerVendor", effectiveVendor,
                    "channel", "LIVE"
            ));
            Instant ts = msg.deterministicAnchor() != null ? msg.deterministicAnchor() : order.getCreatedAt();
            eventPublisher.publishEvent(new RealtimeBridgeEvents.OrderUpdate(
                    liveUserId,
                    order.getId(),
                    order.getSymbol(),
                    OrderState.SUBMITTED.name(),
                    ts
            ));
            log.info("execution.live.submitted orderId={}", order.getId());
            eventPublisher.publishEvent(new OperationalRealtimeEvent("broker_submit", Map.of(
                    "orderId", order.getId().toString(),
                    "userId", liveUserId.toString(),
                    "symbol", order.getSymbol() != null ? order.getSymbol() : "",
                    "mode", "LIVE"
            )));
            return;
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.SUBMITTED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.ACCEPTED, null);
        String simChannel = order.getExecutionMode() != null ? order.getExecutionMode().name() : "SIMULATED";
        executionTraceService.trace(order, ExecutionEventType.BROKER_SUBMITTED, Map.of("channel", simChannel));
        executionTraceService.trace(order, ExecutionEventType.ORDER_ACCEPTED, Map.of("channel", simChannel));
        executionTraceService.trace(order, ExecutionEventType.EXCHANGE_ACK, Map.of("channel", simChannel));

        long fillKey = msg.fillDeterminismKey() != null ? msg.fillDeterminismKey()
                : (order.getId().getMostSignificantBits() ^ order.getId().getLeastSignificantBits());
        Instant anchor = msg.deterministicAnchor() != null ? msg.deterministicAnchor() : order.getCreatedAt();

        StrategySignalEntity sig = order.getSignalId() != null
                ? strategySignalRepository.findById(order.getSignalId()).orElse(null)
                : null;
        BigDecimal atr = sig != null ? sig.getAtrValue() : null;

        List<BigDecimal> fillLots = splitQuantity(order.getQuantity(), Math.max(1, partialFillCount));
        BigDecimal lastFillPrice = null;
        for (int i = 0; i < fillLots.size(); i++) {
            Instant fillAnchor = anchor.plusMillis(orderQueueDelayMs + (long) i * latencyMs);
            MarketdataCandle fillCandle = selectFillCandle(order.getSymbol(), fillAnchor);
            BigDecimal ref = fillCandle != null ? fillCandle.getClosePrice() : safePrice(order);
            BigDecimal fillPrice = applyExecutionCosts(ref, order.getSide(), atr, fillKey, i);
            lastFillPrice = fillPrice;
            Instant ts = fillCandle != null
                    ? fillCandle.getOpenTime().plusMillis(latencyMs)
                    : anchor.plusMillis(latencyMs + (long) i * latencyMs);

            String replaySource = order.getExecutionMode() != null ? order.getExecutionMode().name() : "SIMULATED";
            var ex = executionLedgerService.appendExecution(
                    order,
                    "sim-" + order.getId() + "-" + (i + 1),
                    fillLots.get(i),
                    fillPrice,
                    "SIM",
                    ts,
                    latencyMs,
                    effectiveSlippageBps(atr, ref, fillKey, i),
                    baseSpreadBps,
                    ref,
                    replaySource,
                    order.getBacktestRunId()
            );
            executionGuardTelemetryService.recordFillQuality(order, ex);

            OmsTrade tr = new OmsTrade();
            tr.setOrder(order);
            tr.setExecution(ex);
            tr.setQuantity(fillLots.get(i));
            tr.setPrice(fillPrice);
            tradeRepository.save(tr);

            executionTraceService.trace(order, ExecutionEventType.PARTIAL_FILL, Map.of(
                    "leg", i + 1,
                    "quantity", fillLots.get(i).toPlainString(),
                    "price", fillPrice.toPlainString()
            ));

            if (fillLots.size() > 1 && i < fillLots.size() - 1) {
                order = orderLifecycleService.transition(order.getId(), OrderState.PARTIALLY_FILLED, null);
            }
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.FILLED, null);
        executionTraceService.trace(order, ExecutionEventType.ORDER_FILLED, Map.of(
                "fills", fillLots.size(),
                "lastFillPrice", lastFillPrice != null ? lastFillPrice.toPlainString() : ""
        ));
        executionTraceService.trace(order, ExecutionEventType.POSITION_CLOSED, Map.of(
                "reason", "SIM_ENTRY_COMPLETE"
        ));

        if (order.getBacktestRunId() == null) {
            portfolioAccountingService.applyFill(order.getUserId(), order.getSymbol(), order.getStrategyKey());
        }
        Instant bridgeTs = anchor.plusMillis(latencyMs * fillLots.size());
        eventPublisher.publishEvent(new RealtimeBridgeEvents.OrderUpdate(
                order.getUserId(),
                order.getId(),
                order.getSymbol(),
                OrderState.FILLED.name(),
                bridgeTs
        ));
        eventPublisher.publishEvent(new OperationalRealtimeEvent("execution_fill_complete", Map.of(
                "orderId", order.getId().toString(),
                "userId", order.getUserId().toString(),
                "symbol", order.getSymbol() != null ? order.getSymbol() : "",
                "fills", fillLots.size()
        )));
        log.info("execution.simulated orderId={} lastFillPrice={} fills={}", order.getId(), lastFillPrice, fillLots.size());
    }

    /**
     * Resolves decrypted apiKey + accessToken for the trader's broker account.
     * Returns [null, null] if credentials cannot be resolved — the adapter will then
     * throw an error and the order will transition to FAILED via the caller's exception handler.
     */
    private String[] resolveBrokerCredentials(UUID userId, String vendor) {
        if (!"ZERODHA".equalsIgnoreCase(vendor)) {
            return new String[]{null, null};
        }
        try {
            java.util.Optional<BrokerAccount> accountOpt = brokerAccountRepository
                    .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA");
            if (accountOpt.isEmpty()) {
                log.warn("execution.live.no_broker_account userId={}", userId);
                return new String[]{null, null};
            }
            BrokerAccount account = accountOpt.get();
            if (account.getAccessTokenEnc() == null || account.getAccessTokenEnc().isBlank()) {
                log.warn("execution.live.no_access_token userId={}", userId);
                return new String[]{null, null};
            }
            String accessToken = fieldCipher.decrypt(account.getAccessTokenEnc());
            String apiKey = zerodhaBrokerProperties.getApiKey();
            return new String[]{apiKey, accessToken};
        } catch (Exception ex) {
            log.error("execution.live.cred_resolve_failed userId={} {}", userId, ex.getMessage(), ex);
            return new String[]{null, null};
        }
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

    private BigDecimal effectiveSlippageBps(BigDecimal atr, BigDecimal refPrice, long fillKey, int leg) {
        BigDecimal slip = baseSlippageBps;
        if (atr != null && refPrice != null && refPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal atrPct = atr.divide(refPrice, MC).multiply(BigDecimal.valueOf(10_000), MC);
            slip = slip.add(atrPct.multiply(new BigDecimal("0.35"), MC), MC);
        }
        double u = DeterministicExecutionRng.nextUnit(fillKey, leg, 42);
        BigDecimal jitter = BigDecimal.valueOf(0.97 + u * 0.06);
        return slip.multiply(jitter, MC).max(BigDecimal.ZERO);
    }

    private BigDecimal applyExecutionCosts(BigDecimal referencePrice, String side, BigDecimal atr, long fillKey, int leg) {
        BigDecimal slipBps = effectiveSlippageBps(atr, referencePrice, fillKey, leg);
        BigDecimal slip = referencePrice.multiply(slipBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP);
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
