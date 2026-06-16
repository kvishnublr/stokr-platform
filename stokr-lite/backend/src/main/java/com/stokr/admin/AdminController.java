package com.stokr.admin;

import com.stokr.config.SecurityUtils;
import com.stokr.engine.Deployment;
import com.stokr.engine.ExitManager;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.ErrorLogService;
import com.stokr.risk.KillSwitchService;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Combined admin controller for all admin endpoints.
 * All endpoints require ADMIN role (enforced in SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService userService;
    private final AdminDeploymentService deploymentService;
    private final AdminOrderService orderService;
    private final AdminBrokerHealthService brokerHealthService;
    private final KillSwitchService killSwitchService;
    private final ErrorLogService errorLogService;
    private final StrategyService strategyService;
    private final ExitManager exitManager;
    private final PositionService positionService;

    // ========= Dashboard =========

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(Map.of(
                "userCount", userService.getUserCount(),
                "activeDeployments", deploymentService.getActiveDeploymentCount(),
                "orderStats", orderService.getOrderStats(),
                "brokerStats", brokerHealthService.getBrokerStats(),
                "killSwitchActive", killSwitchService.isActive()
        ));
    }

    // ========= Users =========

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    // ========= Deployments =========

    @GetMapping("/deployments")
    public ResponseEntity<List<Deployment>> getAllDeployments() {
        return ResponseEntity.ok(deploymentService.getAllDeployments());
    }

    @PostMapping("/deployments/{id}/force-stop")
    public ResponseEntity<Deployment> forceStopDeployment(@PathVariable Long id) {
        return ResponseEntity.ok(deploymentService.forceStop(id));
    }

    @PostMapping("/deployments/stop-all")
    public ResponseEntity<Map<String, String>> stopAllDeployments() {
        deploymentService.stopAllDeployments();
        return ResponseEntity.ok(Map.of("status", "All deployments stopped"));
    }

    // ========= Orders =========

    @GetMapping("/orders")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(orderService.getAllOrders(PageRequest.of(page, size)));
    }

    @GetMapping("/orders/pending")
    public ResponseEntity<?> getPendingOrders() {
        return ResponseEntity.ok(orderService.getPendingOrders());
    }

    // ========= Broker Health =========

    @GetMapping("/brokers/health")
    public ResponseEntity<?> getBrokerHealth() {
        return ResponseEntity.ok(brokerHealthService.getBrokerHealthStatus());
    }

    // ========= Kill Switch =========

    @PostMapping("/kill-switch/activate")
    public ResponseEntity<Map<String, Object>> activateKillSwitch(@RequestBody Map<String, String> request) {
        killSwitchService.activate(SecurityUtils.currentUserId(),
                request.getOrDefault("reason", "Admin activated"));
        deploymentService.stopAllDeployments();
        return ResponseEntity.ok(Map.of("status", "Kill switch ACTIVATED",
                "active", true));
    }

    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateKillSwitch() {
        killSwitchService.deactivate();
        return ResponseEntity.ok(Map.of("status", "Kill switch DEACTIVATED",
                "active", false));
    }

    @GetMapping("/kill-switch")
    public ResponseEntity<?> getKillSwitchState() {
        return ResponseEntity.ok(killSwitchService.getState());
    }

    // ========= Error Logs =========

    @GetMapping("/errors")
    public ResponseEntity<?> getErrors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(errorLogService.getErrors(PageRequest.of(page, size)));
    }

    @GetMapping("/errors/{severity}")
    public ResponseEntity<?> getErrorsBySeverity(
            @PathVariable String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(errorLogService.getErrorsBySeverity(severity, PageRequest.of(page, size)));
    }

    // ========= Strategy Toggle =========

    @PatchMapping("/strategies/{id}/toggle")
    public ResponseEntity<?> toggleStrategy(@PathVariable Long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(strategyService.toggleEnabled(id, enabled));
    }

    // ========= Manual Square Off =========

    @PostMapping("/square-off/{deploymentId}")
    public ResponseEntity<Map<String, String>> manualSquareOff(@PathVariable Long deploymentId) {
        Deployment deployment = deploymentService.getAllDeployments().stream()
                .filter(d -> d.getId().equals(deploymentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Deployment not found"));

        List<Position> openPositions = positionService.getOpenPositions(deploymentId);
        for (Position pos : openPositions) {
            exitManager.squareOffPosition(deployment, pos, pos.getAvgPrice());
        }

        return ResponseEntity.ok(Map.of("status",
                "Square-off initiated for " + openPositions.size() + " positions"));
    }
}
