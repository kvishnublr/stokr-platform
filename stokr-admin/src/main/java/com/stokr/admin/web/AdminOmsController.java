package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.domain.OmsExecutionEvent;
import com.stokr.oms.dto.ExecutionEventRowDto;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderDetailDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.OmsSummaryMetricsDto;
import com.stokr.oms.dto.OmsTradeRowDto;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import com.stokr.oms.service.OmsQueryService;
import com.stokr.oms.web.OmsHttpParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/oms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin OMS monitor")
public class AdminOmsController {

    private final OmsQueryService omsQueryService;
    private final OmsExecutionEventRepository executionEventRepository;

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
