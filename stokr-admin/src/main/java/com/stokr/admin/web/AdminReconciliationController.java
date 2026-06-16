package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.reconciliation.BrokerReconciliationService;
import com.stokr.oms.reconciliation.ReconciliationEvent;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Reconciliation")
public class AdminReconciliationController {

    private final ReconciliationEventRepository reconciliationEventRepository;
    private final BrokerReconciliationService brokerReconciliationService;

    @GetMapping("/events")
    @Operation(summary = "List recent reconciliation events")
    public ApiResponse<List<ReconciliationEvent>> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "50") int limit) {
        List<ReconciliationEvent> events = "ALL".equalsIgnoreCase(status)
                ? reconciliationEventRepository.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(0, limit))
                : reconciliationEventRepository.findAllByDeletedFalseAndStatusOrderByCreatedAtDesc(status, PageRequest.of(0, limit));
        return ApiResponse.ok(events, CorrelationIdHolder.get());
    }

    @PostMapping("/events/{id}/acknowledge")
    @Operation(summary = "Acknowledge a reconciliation discrepancy")
    public ApiResponse<ReconciliationEvent> acknowledge(@PathVariable UUID id) {
        ReconciliationEvent ev = reconciliationEventRepository.findById(id)
                .orElseThrow(() -> new com.stokr.common.exception.NotFoundException("ReconciliationEvent not found: " + id));
        ev.setStatus("ACKNOWLEDGED");
        reconciliationEventRepository.save(ev);
        return ApiResponse.ok(ev, CorrelationIdHolder.get());
    }

    @PostMapping("/trigger")
    @Operation(summary = "Trigger a full reconciliation run")
    public ApiResponse<Void> trigger() {
        brokerReconciliationService.reconcileAll();
        return ApiResponse.ok(CorrelationIdHolder.get());
    }
}
