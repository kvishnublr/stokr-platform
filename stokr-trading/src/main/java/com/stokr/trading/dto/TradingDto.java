package com.stokr.trading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class TradingDto {

    // ===================== STRATEGY DTOs =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateStrategyRequest {
        @NotBlank(message = "Name is required")
        private String name;
        private String description;
        private String code;
        private List<Parameter> parameters;
        private List<String> tags;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateStrategyRequest {
        private String name;
        private String description;
        private String code;
        private List<Parameter> parameters;
        private Boolean isActive;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StrategyDto {
        private UUID id;
        private UUID organizationId;
        private UUID creatorId;
        private String name;
        private String description;
        private Boolean isActive;
        private Boolean isPublic;
        private int instanceCount;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Parameter {
        private String name;
        private String type;
        private Object defaultValue;
        private Object minValue;
        private Object maxValue;
        private String description;
    }

    // ===================== INSTANCE DTOs =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateInstanceRequest {
        @NotNull(message = "Symbol is required")
        @NotBlank(message = "Symbol cannot be blank")
        private String symbol;
        private String name;
        private UUID brokerAccountId;
        private String executionMode;
        private BigDecimal allocation;
        private BigDecimal maxPositionSize;
        private BigDecimal riskMultiplier;
        private BigDecimal maxDailyLoss;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateInstanceRequest {
        private String name;
        private String symbol;
        private Boolean enabled;
        private String executionMode;
        private BigDecimal allocation;
        private BigDecimal maxPositionSize;
        private BigDecimal riskMultiplier;
        private BigDecimal maxDailyLoss;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InstanceDto {
        private UUID id;
        private UUID strategyId;
        private UUID userId;
        private UUID brokerAccountId;
        private String name;
        private String symbol;
        private Boolean enabled;
        private String executionMode;
        private BigDecimal allocation;
        private BigDecimal maxPositionSize;
        private BigDecimal riskMultiplier;
        private BigDecimal maxDailyLoss;
        private String status;
        private Instant startedAt;
        private Instant stoppedAt;
        private Instant lastSignalAt;
        private int pendingSignals;
        private int openPositions;
        private BigDecimal totalPnl;
        private Instant createdAt;
    }

    // ===================== SIGNAL DTOs =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateSignalRequest {
        @NotBlank(message = "Symbol is required")
        private String symbol;
        @NotBlank(message = "Signal type is required")
        private String signalType;
        @NotBlank(message = "Side is required")
        private String side;
        private BigDecimal confidence;
        private BigDecimal entryPrice;
        private BigDecimal targetPrice;
        private BigDecimal stopLoss;
        private BigDecimal quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SignalDto {
        private UUID id;
        private UUID instanceId;
        private String symbol;
        private String signalType;
        private String side;
        private BigDecimal confidence;
        private BigDecimal entryPrice;
        private BigDecimal targetPrice;
        private BigDecimal stopLoss;
        private BigDecimal quantity;
        private String status;
        private Instant createdAt;
        private Instant executedAt;
    }

    // ===================== ORDER DTOs =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateOrderRequest {
        private UUID instanceId;
        private UUID signalId;
        @NotBlank(message = "Symbol is required")
        private String symbol;
        @NotBlank(message = "Side is required")
        private String side;
        private String orderType;
        @NotNull(message = "Quantity is required")
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal triggerPrice;
        private String exchange;
        private String productType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderDto {
        private UUID id;
        private UUID instanceId;
        private UUID signalId;
        private UUID userId;
        private String symbol;
        private String side;
        private String orderType;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal filledQuantity;
        private BigDecimal averagePrice;
        private String status;
        private String brokerOrderId;
        private String exchange;
        private String productType;
        private BigDecimal orderValue;
        private Instant createdAt;
        private Instant filledAt;
        private Instant cancelledAt;
        private Instant rejectedAt;
        private String rejectionReason;
    }

    // ===================== POSITION DTOs =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PositionDto {
        private UUID id;
        private UUID instanceId;
        private UUID userId;
        private String symbol;
        private String side;
        private BigDecimal quantity;
        private BigDecimal avgPrice;
        private BigDecimal currentPrice;
        private BigDecimal pnl;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private String exchange;
        private String productType;
        private String status;
        private Instant openedAt;
        private Instant closedAt;
        private BigDecimal positionValue;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PortfolioSummary {
        private int totalPositions;
        private int openPositions;
        private int closedPositions;
        private BigDecimal totalPnl;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private BigDecimal totalInvested;
        private List<PositionDto> positions;
    }
}
