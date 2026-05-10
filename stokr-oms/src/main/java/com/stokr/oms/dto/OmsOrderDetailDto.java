package com.stokr.oms.dto;

import java.util.List;

public record OmsOrderDetailDto(
        OmsOrderSummaryDto order,
        List<OmsExecutionRowDto> executions,
        List<OmsTradeRowDto> trades
) {
}
