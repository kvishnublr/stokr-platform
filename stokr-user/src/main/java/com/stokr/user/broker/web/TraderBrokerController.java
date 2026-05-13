package com.stokr.user.broker.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService.BrokerTestOrderDto;
import com.stokr.user.broker.ZerodhaBrokerOperationsService.BrokerTestOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trader/broker")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TRADER','USER')")
public class TraderBrokerController {

    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;

    @GetMapping("/accounts")
    public ApiResponse<List<ZerodhaBrokerOperationsService.BrokerAccountFundsDto>> accounts(
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(zerodhaBrokerOperationsService.accountsFunds(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/status")
    public ApiResponse<ZerodhaBrokerOperationsService.BrokerStatusDto> status(
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(zerodhaBrokerOperationsService.status(user.getId()), CorrelationIdHolder.get());
    }

    @PostMapping("/disconnect")
    public ApiResponse<Void> disconnect(@AuthenticationPrincipal StokrUserDetails user) {
        zerodhaBrokerOperationsService.disconnect(user.getId());
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    @PostMapping("/test-connection")
    public ApiResponse<ZerodhaBrokerOperationsService.BrokerTestConnectionDto> testConnection(
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(zerodhaBrokerOperationsService.testConnection(user.getId()), CorrelationIdHolder.get());
    }

    @PostMapping("/test-order")
    public ApiResponse<BrokerTestOrderDto> testOrder(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestBody(required = false) BrokerTestOrderRequest body) {
        return ApiResponse.ok(zerodhaBrokerOperationsService.placeTestOrder(user.getId(), body), CorrelationIdHolder.get());
    }
}
