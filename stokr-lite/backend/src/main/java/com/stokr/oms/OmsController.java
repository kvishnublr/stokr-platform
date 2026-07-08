package com.stokr.oms;

import com.stokr.broker.*;
import com.stokr.config.SecurityUtils;
import com.stokr.engine.Deployment;
import com.stokr.engine.DeploymentRepository;
import com.stokr.engine.DeploymentService;
import com.stokr.engine.PaperBroker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OmsController {

    private final OrderService orderService;
    private final PositionService positionService;
    private final PnlService pnlService;
    private final DeploymentService deploymentService;
    private final DeploymentRepository deploymentRepository;
    private final BrokerService brokerService;
    private final PaperBroker paperBroker;

    @GetMapping("/orders")
    public ResponseEntity<Page<Order>> getOrders(
            @RequestParam Long deploymentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(orderService.getOrders(
                deploymentId, PageRequest.of(page, size, Sort.by("createdAt").descending())));
    }

    @GetMapping("/positions/{deploymentId}")
    public ResponseEntity<List<Position>> getPositions(@PathVariable Long deploymentId) {
        return ResponseEntity.ok(positionService.getPositions(deploymentId));
    }

    @GetMapping("/positions/{deploymentId}/open")
    public ResponseEntity<List<Position>> getOpenPositions(@PathVariable Long deploymentId) {
        return ResponseEntity.ok(positionService.getOpenPositions(deploymentId));
    }

    @GetMapping("/pnl/{deploymentId}")
    public ResponseEntity<Map<String, Object>> getPnl(@PathVariable Long deploymentId) {
        return ResponseEntity.ok(pnlService.getDeploymentPnl(deploymentId));
    }

    @PostMapping("/orders/manual")
    public ResponseEntity<Map<String, Object>> placeManualOrder(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.currentUserId();
        String symbol = ((String) body.get("symbol")).toUpperCase().trim();
        String side = ((String) body.get("side")).toUpperCase();
        int quantity = ((Number) body.get("quantity")).intValue();
        String orderType = body.containsKey("orderType") ? ((String) body.get("orderType")).toUpperCase() : "MARKET";
        double price = body.containsKey("price") && body.get("price") != null ? ((Number) body.get("price")).doubleValue() : 0;
        String mode = body.containsKey("mode") ? ((String) body.get("mode")).toUpperCase() : "PAPER";
        Long deploymentId = body.containsKey("deploymentId") && body.get("deploymentId") != null ? ((Number) body.get("deploymentId")).longValue() : null;

        if (symbol.isBlank()) throw new IllegalArgumentException("Symbol is required");
        if (!"BUY".equals(side) && !"SELL".equals(side)) throw new IllegalArgumentException("Side must be BUY or SELL");

        // Find or use deployment
        Deployment deployment = null;
        if (deploymentId != null) {
            deployment = deploymentService.getDeployment(deploymentId, userId);
        } else {
            // Use first active deployment for this user
            var deployments = deploymentService.getUserDeployments(userId).stream()
                    .filter(d -> !"STOPPED".equals(d.getStatus()))
                    .toList();
            if (!deployments.isEmpty()) {
                deployment = deployments.get(0);
            }
        }

        if (deployment == null) {
            deployment = deploymentRepository.save(Deployment.builder()
                    .userId(userId)
                    .strategyId(21L)
                    .mode(mode)
                    .capital(new BigDecimal("100000"))
                    .status("ACTIVE")
                    .build());
        }

        // Create order record
        String productType = "PAPER".equalsIgnoreCase(mode) ? "NRML" : "NRML";
        Order order = orderService.createOrder(deployment.getId(), symbol, side, quantity,
                BigDecimal.valueOf(price), orderType);

        // Pick broker adapter
        BrokerAdapter adapter;
        String accessToken;
        if ("LIVE".equalsIgnoreCase(mode) && deployment.getBrokerAccountId() != null) {
            BrokerAccount account = brokerService.getBrokerAccount(deployment.getBrokerAccountId(), userId);
            adapter = brokerService.getAdapter(account.getBrokerName());
            accessToken = account.getAccessToken();
        } else {
            adapter = paperBroker;
            accessToken = "paper";
            mode = "PAPER";
        }

        // Build request
        BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange("NSE")
                .side("BUY".equals(side) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL)
                .quantity(quantity)
                .price(price > 0 ? price : null)
                .orderType("LIMIT".equals(orderType) ? BrokerOrderRequest.OrderType.LIMIT : BrokerOrderRequest.OrderType.MARKET)
                .productType(productType)
                .build();

        // Place order
        BrokerOrderResponse response = adapter.placeOrder(accessToken, request);

        if (response.isSuccess()) {
            orderService.completeOrder(order, response.orderId(), BigDecimal.valueOf(price > 0 ? price : 0), quantity);
            log.info("Manual order placed: {} {} {} qty={} @ {} via {}", side, symbol, orderType, quantity, price, mode);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "orderId", order.getId(),
                    "brokerOrderId", response.orderId(),
                    "message", response.message(),
                    "symbol", symbol,
                    "side", side,
                    "quantity", quantity,
                    "price", price,
                    "mode", mode,
                    "deploymentId", deployment.getId()
            ));
        } else {
            orderService.rejectOrder(order, response.message());
            log.warn("Manual order rejected: {} {} {} - {}", side, symbol, quantity, response.message());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "orderId", order.getId(),
                    "message", response.message(),
                    "symbol", symbol,
                    "side", side,
                    "quantity", quantity,
                    "mode", mode
            ));
        }
    }
}
