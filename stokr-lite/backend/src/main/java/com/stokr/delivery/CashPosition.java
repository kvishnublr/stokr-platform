package com.stokr.delivery;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cash_positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    @Column(name = "strategy_type")
    private String strategyType;

    @Builder.Default
    private String side = "BUY";

    private Integer quantity;

    @Column(name = "entry_price")
    private BigDecimal entryPrice;

    @Column(name = "exit_price")
    private BigDecimal exitPrice;

    @Column(name = "target_price")
    private BigDecimal targetPrice;

    @Column(name = "stop_loss_price")
    private BigDecimal stopLossPrice;

    @Column(name = "current_pnl")
    private BigDecimal currentPnl;

    @Builder.Default
    private String status = "OPEN";

    private String broker;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "entered_at")
    private LocalDateTime enteredAt;

    @Column(name = "exited_at")
    private LocalDateTime exitedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("symbol", symbol);
        map.put("strategyType", strategyType);
        map.put("side", side);
        map.put("quantity", quantity);
        map.put("entryPrice", entryPrice != null ? entryPrice.doubleValue() : null);
        map.put("exitPrice", exitPrice != null ? exitPrice.doubleValue() : null);
        map.put("targetPrice", targetPrice != null ? targetPrice.doubleValue() : null);
        map.put("stopLossPrice", stopLossPrice != null ? stopLossPrice.doubleValue() : null);
        map.put("currentPnl", currentPnl != null ? currentPnl.doubleValue() : 0);
        map.put("status", status);
        map.put("broker", broker);
        map.put("orderId", orderId);
        map.put("errorMessage", errorMessage);
        map.put("enteredAt", enteredAt != null ? enteredAt.toString() : null);
        map.put("exitedAt", exitedAt != null ? exitedAt.toString() : null);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        return map;
    }
}
