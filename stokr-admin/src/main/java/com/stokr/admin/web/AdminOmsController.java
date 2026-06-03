package com.stokr.admin.web;

import com.stokr.admin.signal.AdminOmsStatsDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.oms.dto.ExecutionEventRowDto;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderDetailDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.OmsSummaryMetricsDto;
import com.stokr.oms.dto.OmsTradeRowDto;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import com.stokr.oms.service.OmsQueryService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.web.OmsHttpParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/oms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin OMS monitor")
public class AdminOmsController {

    private final OmsQueryService omsQueryService;
    private final OmsExecutionEventRepository executionEventRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final OrderLifecycleService orderLifecycleService;

    @GetMapping("/stats")
    @Operation(summary = "Aggregate OMS stats for today or custom window")
    public ApiResponse<AdminOmsStatsDto> omsStats(
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "REAL") String dataScope
    ) {
        Instant from = since != null ? since
                : Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
        List<Object[]> rows = omsOrderRepository.computeStats(from, AnalyticsDataScope.parse(dataScope).name());
        AdminOmsStatsDto dto;
        if (rows.isEmpty()) {
            dto = new AdminOmsStatsDto(0, 0, 0, 0, 0, 0, 0);
        } else {
            Object[] r = rows.get(0);
            dto = new AdminOmsStatsDto(
                    toLong(r[0]), toLong(r[1]), toLong(r[2]),
                    toLong(r[3]), toLong(r[4]), toLong(r[5]), toLong(r[6])
            );
        }
        return ApiResponse.ok(dto, CorrelationIdHolder.get());
    }

    @GetMapping("/orders")
    @Operation(summary = "Global order monitor (optional user filter)")
    public ApiResponse<PageResponse<OmsOrderSummaryDto>> orders(
            @PageableDefault(size = 50) Pageable pageable,
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "strategyKey", required = false) String strategyKey,
            @RequestParam(value = "brokerVendor", required = false) String brokerVendor,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "executionMode", required = false) String executionMode,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "pipelineMode", required = false, defaultValue = "ALL") String pipelineMode
    ) {
        OmsReadParams p = OmsHttpParams.parse(symbol, strategyKey, brokerVendor, state, executionMode, from, to, pipelineMode);
        var page = omsQueryService.pageOrders(userId, p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Order detail (admin)")
    public ApiResponse<OmsOrderDetailDto> orderDetail(@PathVariable("id") UUID id) {
        return ApiResponse.ok(omsQueryService.orderDetail(null, id, true), CorrelationIdHolder.get());
    }

    @GetMapping("/executions")
    public ApiResponse<PageResponse<OmsExecutionRowDto>> executions(
            @PageableDefault(size = 50) Pageable pageable,
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "strategyKey", required = false) String strategyKey,
            @RequestParam(value = "brokerVendor", required = false) String brokerVendor,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "executionMode", required = false) String executionMode,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "pipelineMode", required = false, defaultValue = "ALL") String pipelineMode
    ) {
        OmsReadParams p = OmsHttpParams.parse(symbol, strategyKey, brokerVendor, state, executionMode, from, to, pipelineMode);
        var page = omsQueryService.pageExecutions(userId, p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/trades")
    public ApiResponse<PageResponse<OmsTradeRowDto>> trades(
            @PageableDefault(size = 50) Pageable pageable,
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "strategyKey", required = false) String strategyKey,
            @RequestParam(value = "brokerVendor", required = false) String brokerVendor,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "executionMode", required = false) String executionMode,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "pipelineMode", required = false, defaultValue = "ALL") String pipelineMode
    ) {
        OmsReadParams p = OmsHttpParams.parse(symbol, strategyKey, brokerVendor, state, executionMode, from, to, pipelineMode);
        var page = omsQueryService.pageTrades(userId, p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/orders/{id}/events")
    @Operation(summary = "Execution events for an order (admin)")
    public ApiResponse<java.util.List<ExecutionEventRowDto>> orderEvents(@PathVariable("id") UUID id) {
        var events = executionEventRepository.findByOrder_IdAndDeletedFalseOrderByStreamSequenceAsc(id);
        var dtos = events.stream().map(e -> new ExecutionEventRowDto(
                e.getId(), e.getEventType().name(), e.getEventPayloadJson(),
                e.getStreamSequence(), e.getCorrelationId(), e.getCreatedAt()
        )).toList();
        return ApiResponse.ok(dtos, CorrelationIdHolder.get());
    }

    @GetMapping("/reject-reasons")
    @Operation(summary = "Top reject reasons by count (optional since window)")
    public ApiResponse<List<Map<String, Object>>> rejectReasons(
            @RequestParam(defaultValue = "REAL") String dataScope,
            @RequestParam(required = false) Instant since
    ) {
        List<Object[]> rows = omsOrderRepository.countRejectionsByReason(
                AnalyticsDataScope.parse(dataScope).name(), since);
        List<Map<String, Object>> result = rows.stream()
                .map(r -> Map.<String, Object>of("reason", r[0], "count", ((Number) r[1]).longValue()))
                .toList();
        return ApiResponse.ok(result, CorrelationIdHolder.get());
    }

    @PostMapping("/stuck-orders/expire")
    @Operation(summary = "Force-expire all orders stuck in pre-terminal states for >N minutes (default 5)")
    public ApiResponse<Map<String, Object>> expireStuckOrders(
            @RequestParam(defaultValue = "5") int stuckMinutes
    ) {
        int expired = orderLifecycleService.forceExpireStuckOrders(stuckMinutes);
        return ApiResponse.ok(Map.of("expired", expired, "stuckMinutes", stuckMinutes), CorrelationIdHolder.get());
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    @GetMapping("/summary")
    public ApiResponse<OmsSummaryMetricsDto> summary(
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "strategyKey", required = false) String strategyKey,
            @RequestParam(value = "brokerVendor", required = false) String brokerVendor,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "executionMode", required = false) String executionMode,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "pipelineMode", required = false, defaultValue = "ALL") String pipelineMode
    ) {
        OmsReadParams p = OmsHttpParams.parse(symbol, strategyKey, brokerVendor, state, executionMode, from, to, pipelineMode);
        return ApiResponse.ok(omsQueryService.summarize(userId, p), CorrelationIdHolder.get());
    }
}
