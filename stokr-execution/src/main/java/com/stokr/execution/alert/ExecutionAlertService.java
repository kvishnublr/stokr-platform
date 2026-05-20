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
        publish("RECONCILIATION_ALERT", null, symbol, null, text);
    }

    private void publish(String alertType, String strategyKey, String symbol, OmsOrder order, String text) {
        boolean telegramEnabled = isTelegramEnabled(strategyKey);
        ExecutionAlertLog log = new ExecutionAlertLog();
        log.setAlertType(alertType);
        log.setStrategyKey(strategyKey);
        log.setSymbol(symbol);
        if (order != null) {
            log.setOrderId(order.getId());
            log.setUserId(order.getUserId());
        }
        try {
            log.setPayloadJson(objectMapper.writeValueAsString(
                    Map.of("text", text, "telegramEnabled", telegramEnabled)));
        } catch (Exception ignored) {
            log.setPayloadJson("{\"text\":\"" + text + "\"}");
        }
        alertLogRepository.save(log);

        if (telegramEnabled) {
            eventPublisher.publishEvent(new ExecutionAlertEvent(
                    alertType, strategyKey, symbol,
                    order != null ? order.getId() : null,
                    order != null ? order.getUserId() : null,
                    text));
        }
        this.log.info("alert.published type={} strategy={} symbol={}", alertType, strategyKey, symbol);
    }

    private boolean isTelegramEnabled(String strategyKey) {
        if (strategyKey == null) return false;
        Optional<StrategyExecutionConfig> cfg = strategyExecutionConfigService.getByStrategyKey(strategyKey);
        return cfg.map(StrategyExecutionConfig::isTelegramEnabled).orElse(false);
    }
}
