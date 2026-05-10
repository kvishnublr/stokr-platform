package com.stokr.oms.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderDetailDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.OmsSummaryMetricsDto;
import com.stokr.oms.dto.OmsTradeRowDto;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.service.OmsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/oms")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "OMS (read)")
public class OmsApiController {

    private final OmsQueryService omsQueryService;

    @GetMapping("/orders")
    @Operation(summary = "Paged order history for current user")
    public ApiResponse<PageResponse<OmsOrderSummaryDto>> orders(
            @PageableDefault(size = 25) Pageable pageable,
            @AuthenticationPrincipal StokrUserDetails user,
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
        var page = omsQueryService.pageOrders(user.getId(), p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Order detail with executions and trades")
    public ApiResponse<OmsOrderDetailDto> orderDetail(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(omsQueryService.orderDetail(user.getId(), id, false), CorrelationIdHolder.get());
    }

    @GetMapping("/executions")
    @Operation(summary = "Paged executions for current user")
    public ApiResponse<PageResponse<OmsExecutionRowDto>> executions(
            @PageableDefault(size = 25) Pageable pageable,
            @AuthenticationPrincipal StokrUserDetails user,
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
        var page = omsQueryService.pageExecutions(user.getId(), p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/trades")
    @Operation(summary = "Paged trades for current user")
    public ApiResponse<PageResponse<OmsTradeRowDto>> trades(
            @PageableDefault(size = 25) Pageable pageable,
            @AuthenticationPrincipal StokrUserDetails user,
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
        var page = omsQueryService.pageTrades(user.getId(), p, pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/summary")
    @Operation(summary = "Aggregate OMS metrics for current user")
    public ApiResponse<OmsSummaryMetricsDto> summary(
            @AuthenticationPrincipal StokrUserDetails user,
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
        return ApiResponse.ok(omsQueryService.summarize(user.getId(), p), CorrelationIdHolder.get());
    }
}
