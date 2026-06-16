package com.stokr.oms;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OmsController {

    private final OrderService orderService;
    private final PositionService positionService;
    private final PnlService pnlService;

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
}
