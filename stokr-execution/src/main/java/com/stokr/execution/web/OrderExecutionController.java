package com.stokr.execution.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.service.OrderPlacementService;
import com.stokr.oms.domain.OmsOrder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderExecutionController {

    private final OrderPlacementService orderPlacementService;

    @PostMapping
    public ApiResponse<OmsOrder> place(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return ApiResponse.ok(orderPlacementService.place(user.getId(), request), CorrelationIdHolder.get());
    }
}
