package com.stokr.oms.trace;

import java.util.List;
import java.util.UUID;

public interface ExecutionTimelineProjection {

    List<ExecutionTraceEvent> timelineForOrder(UUID orderId);
}
