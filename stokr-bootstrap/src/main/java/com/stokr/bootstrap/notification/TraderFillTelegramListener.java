package com.stokr.bootstrap.notification;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.execution.comparison.TradeLifecycleReconciliationService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.user.telegram.TelegramDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends trader Telegram alerts on entry/exit fills. */
@Component
@RequiredArgsConstructor
@Slf4j
public class TraderFillTelegramListener {

    private static final List<String> FILL_TOPICS = List.of("broker_fill_synced", "execution_fill_complete");

    private final OmsOrderRepository omsOrderRepository;
    private final OmsTradeRepository omsTradeRepository;
    private final TelegramDeliveryService telegramDeliveryService;

    @EventListener
    @Transactional(readOnly = true)
    public void onFillEvent(OperationalRealtimeEvent event) {
        if (event == null || event.topic() == null || !FILL_TOPICS.contains(event.topic())) {
            return;
        }
        Map<String, Object> payload = event.payload();
        if (payload == null) {
            return;
        }
        UUID orderId = parseUuid(payload.get("orderId"));
        if (orderId == null) {
            return;
        }
        OmsOrder order = omsOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.isDeleted() || order.getUserId() == null) {
            return;
        }
        if (order.isTestTrade() || order.getBacktestRunId() != null) {
            return;
        }
        BigDecimal fillPrice = resolveFillPrice(order);
        boolean exit = TradeLifecycleReconciliationService.isExitOrder(order);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("symbol", order.getSymbol() != null ? order.getSymbol() : "");
        fields.put("side", order.getSide() != null ? order.getSide() : "");
        fields.put("qty", order.getQuantity() != null ? order.getQuantity().stripTrailingZeros().toPlainString() : "");
        fields.put("price", fillPrice != null ? fillPrice.stripTrailingZeros().toPlainString() : "");
        fields.put("strategy", order.getStrategyKey() != null ? order.getStrategyKey() : "");
        fields.put("orderId", order.getId().toString());
        String mode = order.getExecutionMode() != null ? order.getExecutionMode().name() : "LIVE";
        telegramDeliveryService.deliverTraderTradeFill(order.getUserId(), mode, exit, fields);
    }

    private BigDecimal resolveFillPrice(OmsOrder order) {
        List<OmsTrade> trades = omsTradeRepository.findByOrder_IdOrderByCreatedAtAsc(order.getId());
        if (trades.isEmpty()) {
            return null;
        }
        return trades.getLast().getPrice();
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
