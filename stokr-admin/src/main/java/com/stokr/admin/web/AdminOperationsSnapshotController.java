package com.stokr.admin.web;

import com.stokr.admin.dto.OperationsSnapshotDto;
import com.stokr.admin.service.AdminOperationalSnapshotService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/operations")
@RequiredArgsConstructor
public class AdminOperationsSnapshotController {

    private final AdminOperationalSnapshotService adminOperationalSnapshotService;

    @GetMapping("/snapshot")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OperationsSnapshotDto> snapshot() {
        return ApiResponse.ok(adminOperationalSnapshotService.snapshot(), CorrelationIdHolder.get());
    }
}
