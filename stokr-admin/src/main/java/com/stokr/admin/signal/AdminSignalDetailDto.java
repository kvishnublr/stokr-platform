package com.stokr.admin.signal;

import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.trace.ExecutionTraceEvent;

import java.util.List;

public record AdminSignalDetailDto(
        AdminSignalDto signal,
        List<OmsOrderSummaryDto> linkedOrders,
        List<ExecutionTraceEvent> executionTimeline
) {
}
