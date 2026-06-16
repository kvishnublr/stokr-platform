package com.stokr.trading.controller;

import com.stokr.trading.dto.TradingDto.*;
import com.stokr.trading.service.OrderService;
import com.stokr.trading.service.PositionService;
import com.stokr.trading.service.StrategyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TradingController {

    private final StrategyService strategyService;
    private final OrderService orderService;
    private final PositionService positionService;

    // ===================== STRATEGIES =====================

    @GetMapping("/strategies")
    public ResponseEntity<List<StrategyDto>> getStrategies(
            @RequestAttribute("userId") UUID userId,
            @RequestAttribute("userRole") String role) {
        // For now, return all strategies (will be filtered by org in real impl)
        return ResponseEntity.ok(strategyService.getStrategiesByOrganization(userId));
    }

    @GetMapping("/strategies/{id}")
    public ResponseEntity<StrategyDto> getStrategy(@PathVariable UUID id) {
        return ResponseEntity.ok(strategyService.getStrategy(id));
    }

    @PostMapping("/strategies")
    public ResponseEntity<StrategyDto> createStrategy(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody CreateStrategyRequest request) {
        // TODO: Get org from user context
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(strategyService.createStrategy(null, userId, request));
    }

    @PutMapping("/strategies/{id}")
    public ResponseEntity<StrategyDto> updateStrategy(
            @PathVariable UUID id,
            @RequestBody UpdateStrategyRequest request) {
        return ResponseEntity.ok(strategyService.updateStrategy(id, request));
    }

    @DeleteMapping("/strategies/{id}")
    public ResponseEntity<Void> deleteStrategy(@PathVariable UUID id) {
        strategyService.deleteStrategy(id);
        return ResponseEntity.noContent().build();
    }

    // ===================== INSTANCES =====================

    @GetMapping("/instances")
    public ResponseEntity<List<InstanceDto>> getInstances(
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(strategyService.getInstancesByUser(userId));
    }

    @GetMapping("/instances/{id}")
    public ResponseEntity<InstanceDto> getInstance(@PathVariable UUID id) {
        return ResponseEntity.ok(strategyService.getInstance(id));
    }

    @PostMapping("/instances")
    public ResponseEntity<InstanceDto> createInstance(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody CreateInstanceRequest request) {
        // TODO: Get org from user context
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(strategyService.createInstance(null, userId, null, request));
    }

    @PutMapping("/instances/{id}")
    public ResponseEntity<InstanceDto> updateInstance(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId,
            @RequestBody UpdateInstanceRequest request) {
        return ResponseEntity.ok(strategyService.updateInstance(id, userId, request));
    }

    @DeleteMapping("/instances/{id}")
    public ResponseEntity<Void> deleteInstance(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {
        strategyService.deleteInstance(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/instances/{id}/start")
    public ResponseEntity<InstanceDto> startInstance(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(strategyService.startInstance(id, userId));
    }

    @PostMapping("/instances/{id}/stop")
    public ResponseEntity<InstanceDto> stopInstance(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(strategyService.stopInstance(id, userId));
    }

    @PostMapping("/instances/{id}/pause")
    public ResponseEntity<InstanceDto> pauseInstance(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(strategyService.pauseInstance(id, userId));
    }

    // ===================== SIGNALS =====================

    @GetMapping("/instances/{id}/signals")
    public ResponseEntity<List<SignalDto>> getSignals(@PathVariable UUID id) {
        return ResponseEntity.ok(strategyService.getSignalsByInstance(id));
    }

    @PostMapping("/instances/{id}/signals")
    public ResponseEntity<SignalDto> createSignal(
            @PathVariable UUID id,
            @Valid @RequestBody CreateSignalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(strategyService.createSignal(id, request));
    }

    // ===================== ORDERS =====================

    @GetMapping("/orders")
    public ResponseEntity<List<OrderDto>> getOrders(
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(orderService.getOrdersByUser(userId));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderDto> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderDto> createOrder(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(userId, request));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<OrderDto> cancelOrder(
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(orderService.cancelOrder(id, userId));
    }

    // ===================== POSITIONS =====================

    @GetMapping("/positions")
    public ResponseEntity<List<PositionDto>> getPositions(
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(positionService.getPositionsByUser(userId));
    }

    @GetMapping("/positions/{symbol}")
    public ResponseEntity<PositionDto> getPositionBySymbol(
            @RequestAttribute("userId") UUID userId,
            @PathVariable String symbol) {
        return ResponseEntity.ok(positionService.getPositionBySymbol(userId, symbol.toUpperCase()));
    }

    @GetMapping("/portfolio/summary")
    public ResponseEntity<TradingDto.PortfolioSummary> getPortfolioSummary(
            @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(positionService.getPortfolioSummary(userId));
    }
}
