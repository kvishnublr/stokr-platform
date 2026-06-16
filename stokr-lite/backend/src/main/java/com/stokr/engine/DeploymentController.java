package com.stokr.engine;

import com.stokr.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;

    @GetMapping
    public ResponseEntity<List<Deployment>> getMyDeployments() {
        return ResponseEntity.ok(deploymentService.getUserDeployments(SecurityUtils.currentUserId()));
    }

    @GetMapping("/active")
    public ResponseEntity<List<Deployment>> getActiveDeployments() {
        return ResponseEntity.ok(deploymentService.getActiveDeployments(SecurityUtils.currentUserId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Deployment> getDeployment(@PathVariable Long id) {
        return ResponseEntity.ok(deploymentService.getDeployment(id, SecurityUtils.currentUserId()));
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

    public record DeployRequest(Long strategyId, Long brokerAccountId, String mode, BigDecimal capital) {}
}
