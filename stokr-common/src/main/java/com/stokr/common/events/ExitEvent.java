package com.stokr.common.events;

import org.springframework.context.ApplicationEvent;
import com.stokr.common.domain.ExitReason;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
public class ExitEvent extends ApplicationEvent {
    private final UUID positionId;
    private final UUID userId;
    private final String symbol;
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final ExitReason exitReason;
    private final Instant decisionTime;
    private UUID orderId;

    public ExitEvent(Object source, UUID positionId, UUID userId, String symbol,
            BigDecimal entryPrice, BigDecimal exitPrice, ExitReason exitReason, Instant decisionTime) {
        super(source);
        this.positionId = positionId;
        this.userId = userId;
        this.symbol = symbol;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.exitReason = exitReason;
        this.decisionTime = decisionTime;
        this.orderId = null;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public BigDecimal calculatePnL(BigDecimal quantity) {
        if (quantity.signum() > 0) {
            return exitPrice.subtract(entryPrice).multiply(quantity);
        } else {
            return entryPrice.subtract(exitPrice).multiply(quantity.abs());
        }
    }
}
