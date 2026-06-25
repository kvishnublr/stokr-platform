package com.stokr.engine;

import com.stokr.broker.BrokerService;
import com.stokr.config.SecurityUtils;
import com.stokr.oms.PositionService;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final StrategyService   strategyService;
    private final BrokerService     brokerService;
    private final PositionService   positionService;
    private final SignalRepository  signalRepository;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyDeployments() {
        Long userId = SecurityUtils.currentUserId();
        List<Deployment> list = deploymentService.getUserDeployments(userId);
        return ResponseEntity.ok(list.stream().map(this::enrich).toList());
    }

    @GetMapping("/active")
    public ResponseEntity<List<Map<String, Object>>> getActiveDeployments() {
        Long userId = SecurityUtils.currentUserId();
        List<Deployment> list = deploymentService.getActiveDeployments(userId);
        return ResponseEntity.ok(list.stream().map(this::enrich).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDeployment(@PathVariable Long id) {
        return ResponseEntity.ok(enrich(deploymentService.getDeployment(id, SecurityUtils.currentUserId())));
    }

    @PostMapping
    public ResponseEntity<Deployment> deploy(@RequestBody DeployRequest request) {
        return ResponseEntity.ok(deploymentService.deploy(
                SecurityUtils.currentUserId(),
                request.strategyId(),
                request.brokerAccountId(),
                request.mode(),
                request.capital()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Deployment> stopDeployment(@PathVariable Long id) {
        return ResponseEntity.ok(deploymentService.stopDeployment(id, SecurityUtils.currentUserId()));
    }

    private Map<String, Object> enrich(Deployment d) {
        // Strategy name
        String strategyName = null;
        try { strategyName = strategyService.getStrategy(d.getStrategyId()).getName(); } catch (Exception ignored) {}

        // Broker name
        String brokerName = null;
        if (d.getBrokerAccountId() != null) {
            try { brokerName = brokerService.getBrokerAccount(d.getBrokerAccountId(), d.getUserId()).getBrokerName(); } catch (Exception ignored) {}
        }

        // Open positions count
        int openPositions = 0;
        BigDecimal todayPnl = BigDecimal.ZERO;
        try {
            var positions = positionService.getOpenPositions(d.getId());
            openPositions = positions.size();
            todayPnl = positions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception ignored) {}

        // Signals today
        long signalsToday = 0;
        Instant lastSignalAt = null;
        try {
            Instant startOfDay = LocalDate.now(IST).atStartOfDay(IST).toInstant();
            signalsToday = signalRepository.countByDeploymentIdAndCreatedAtAfter(d.getId(), startOfDay);
            lastSignalAt = signalRepository.findFirstByDeploymentIdOrderByCreatedAtDesc(d.getId())
                .map(SignalEntity::getCreatedAt).orElse(null);
        } catch (Exception ignored) {}

        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("id",              d.getId());
        result.put("userId",          d.getUserId());
        result.put("strategyId",      d.getStrategyId());
        result.put("strategyName",    strategyName);
        result.put("brokerAccountId", d.getBrokerAccountId());
        result.put("brokerName",      brokerName);
        result.put("mode",            d.getMode());
        result.put("capital",         d.getCapital());
        result.put("status",          d.getStatus());
        result.put("createdAt",       d.getCreatedAt());
        result.put("updatedAt",       d.getUpdatedAt());
        result.put("openPositions",   openPositions);
        result.put("todayPnl",        todayPnl);
        result.put("signalsToday",    signalsToday);
        result.put("lastSignalAt",    lastSignalAt);
        return result;
    }

    public record DeployRequest(Long strategyId, Long brokerAccountId, String mode, BigDecimal capital) {}
}
