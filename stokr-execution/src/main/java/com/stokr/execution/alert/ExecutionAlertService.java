package com.stokr.execution.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.events.ExecutionAlertEvent;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionAlertService {

    private final ExecutionAlertLogRepository alertLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyExecutionConfigService strategyExecutionConfigService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void onLiveFill(OmsOrder order, BigDecimal fillPrice) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) return;
        String text = "LIVE FILL: " + order.getStrategyKey()
                + " " + order.getSymbol() + " " + order.getSide()
                + " qty=" + order.getQuantity() + " @ " + fillPrice;
        publish("LIVE_FILL", order.getStrategyKey(), order.getSymbol(), order, text);
    }

    @Transactional
    public void onLiveRiskRejected(OmsOrder order, RiskDecision decision) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) return;
        String text = "RISK REJECTED: " + order.getStrategyKey()
                + " " + order.getSymbol() + " reason=" + decision.reasonCode();
        publish("RISK_REJECTED", order.getStrategyKey(), order.getSymbol(), order, text);
    }

    @Transactional
    public void onLiveBrokerRejected(OmsOrder order, String reason) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) return;
        String text = "BROKER REJECTED: " + order.getStrategyKey()
                + " " + order.getSymbol() + " " + reason;
        publish("BROKER_REJECTED", order.getStrategyKey(), order.getSymbol(), order, text);
    }

    /** Pre-broker rejections: risk engine, execution guards, duplicate keys, position caps. */
    @Transactional
    public void onLiveOrderRejected(OmsOrder order, String reason) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) return;
        String text = "ORDER REJECTED: " + order.getStrategyKey()
                + " " + order.getSymbol() + " " + order.getSide()
                + " — " + reason;
        publish("ORDER_REJECTED", order.getStrategyKey(), order.getSymbol(), order, text);
    }

    @Transactional
    public void onDailyLossBreach(String strategyKey, BigDecimal todayPnl, BigDecimal limit) {
        String text = "DAILY LOSS LIMIT: " + strategyKey + " loss=" + todayPnl + " limit=" + limit;
        publish("DAILY_LOSS_BREACH", strategyKey, null, null, text);
    }

    @Transactional
    public void onEmergencyStop(String strategyKey) {
        String text = "EMERGENCY STOP: " + strategyKey;
        publish("EMERGENCY_STOP", strategyKey, null, null, text);
    }

    @Transactional
    public void onReconciliationAlert(String symbol, BigDecimal brokerQty, BigDecimal internalQty) {
        String text = "RECONCILIATION: " + symbol + " broker=" + brokerQty + " internal=" + internalQty;
        // DISABLED: publish("RECONCILIATION_ALERT", null, symbol, null, text);
    }

    /** Admin Test Signal Lab: persist alert log even when Telegram is disabled for the strategy. */
    @Transactional
    public void onTestLabExecution(OmsOrder order, String detail) {
        if (order == null) {
            return;
        }
        String text = "TEST_LAB: " + order.getStrategyKey()
                + " " + order.getSymbol() + " " + order.getSide()
                + " mode=" + (order.getExecutionMode() != null ? order.getExecutionMode().name() : "?")
                + " state=" + (order.getState() != null ? order.getState().name() : "?")
                + (detail != null && !detail.isBlank() ? " — " + detail : "");
        publish("TEST_LAB_EXECUTION", order.getStrategyKey(), order.getSymbol(), order, text);
    }

    private void publish(String alertType, String strategyKey, String symbol, OmsOrder order, String text) {
        boolean telegramEnabled = isTelegramEnabled(strategyKey);
        ExecutionAlertLog entry = new ExecutionAlertLog();
        entry.setAlertType(alertType);
        entry.setStrategyKey(strategyKey);
        entry.setSymbol(symbol);
        if (order != null) {
            entry.setOrderId(order.getId());
            entry.setUserId(order.getUserId());
        }
        try {
            entry.setPayloadJson(objectMapper.writeValueAsString(
                    Map.of("text", text, "telegramEnabled", telegramEnabled)));
        } catch (Exception ignored) {
            entry.setPayloadJson("{\"text\":\"" + text + "\"}");
        }
        alertLogRepository.save(entry);

        if (telegramEnabled) {
            eventPublisher.publishEvent(new ExecutionAlertEvent(
                    alertType, strategyKey, symbol,
                    order != null ? order.getId() : null,
                    order != null ? order.getUserId() : null,
                    text));
        }
        log.info("alert.published type={} strategy={} symbol={}", alertType, strategyKey, symbol);
    }

    private boolean isTelegramEnabled(String strategyKey) {
        if (strategyKey == null) return false;
        Optional<StrategyExecutionConfig> cfg = strategyExecutionConfigService.getByStrategyKey(strategyKey);
        return cfg.map(StrategyExecutionConfig::isTelegramEnabled).orElse(false);
    }
}
