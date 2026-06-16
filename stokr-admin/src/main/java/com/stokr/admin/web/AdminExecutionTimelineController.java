package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.trace.ExecutionTimelineProjection;
import com.stokr.oms.trace.ExecutionTraceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/operations")
@RequiredArgsConstructor
public class AdminExecutionTimelineController {

    private final ExecutionTimelineProjection executionTimelineProjection;

    @GetMapping("/orders/{orderId}/execution-timeline")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ExecutionTraceEvent>> timeline(@PathVariable UUID orderId) {
        return ApiResponse.ok(executionTimelineProjection.timelineForOrder(orderId), CorrelationIdHolder.get());
    }
}
