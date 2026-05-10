package com.stokr.admin.web;

import com.stokr.backtest.service.ReplayValidationService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.oms.service.OmsReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/finance")
@RequiredArgsConstructor
public class AdminFinanceController {

    private final OmsReconciliationService reconciliationService;
    private final ReplayValidationService replayValidationService;
    private final RabbitAdmin rabbitAdmin;

    @GetMapping("/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OmsReconciliationService.ReconciliationReport> reconcile(@RequestParam("userId") UUID userId) {
        return ApiResponse.ok(reconciliationService.reconcileUser(userId), CorrelationIdHolder.get());
    }

    @GetMapping("/replay/validation")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ReplayValidationService.ReplayValidationReport> replayValidation(@RequestParam("runId") UUID runId) {
        return ApiResponse.ok(replayValidationService.validateRun(runId), CorrelationIdHolder.get());
    }

    @GetMapping("/queues")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> queues() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(PipelineQueues.STRATEGY_SIGNAL, props(PipelineQueues.STRATEGY_SIGNAL));
        body.put(PipelineQueues.OMS_ORDER, props(PipelineQueues.OMS_ORDER));
        body.put(PipelineQueues.EXECUTION, props(PipelineQueues.EXECUTION));
        body.put(PipelineQueues.STRATEGY_SIGNAL_DLQ, props(PipelineQueues.STRATEGY_SIGNAL_DLQ));
        body.put(PipelineQueues.OMS_ORDER_DLQ, props(PipelineQueues.OMS_ORDER_DLQ));
        body.put(PipelineQueues.EXECUTION_DLQ, props(PipelineQueues.EXECUTION_DLQ));
        return ApiResponse.ok(body, CorrelationIdHolder.get());
    }

    @GetMapping("/queues/{queue}/depth")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> queueDepth(@PathVariable("queue") String queue) {
        return ApiResponse.ok(Map.of("queue", queue, "properties", props(queue)), CorrelationIdHolder.get());
    }

    private Map<String, Object> props(String queue) {
        Properties p = rabbitAdmin.getQueueProperties(queue);
        Map<String, Object> map = new LinkedHashMap<>();
        if (p != null) {
            for (String name : p.stringPropertyNames()) {
                map.put(name, p.getProperty(name));
            }
        }
        return map;
    }
}
