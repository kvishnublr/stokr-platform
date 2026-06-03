package com.stokr.common.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPipelineRuntimeReadinessServiceTest {

    @Test
    void liveRoutingAllowedWhenSyncOmsDispatchEnabledWithoutRabbit() {
        ExecutionPipelineRuntimeReadinessService service = new ExecutionPipelineRuntimeReadinessService();
        ReflectionTestUtils.setField(service, "rabbitListenersEnabled", false);
        ReflectionTestUtils.setField(service, "syncOmsDispatch", true);
        ReflectionTestUtils.setField(service, "pollExecutionMode", "LIVE");

        assertTrue(service.canRouteExecutionMode("LIVE"));
        assertTrue(service.snapshot().executionPipelineActive());
    }

    @Test
    void liveRoutingBlockedWhenRabbitOffAndSyncDispatchOff() {
        ExecutionPipelineRuntimeReadinessService service = new ExecutionPipelineRuntimeReadinessService();
        ReflectionTestUtils.setField(service, "rabbitListenersEnabled", false);
        ReflectionTestUtils.setField(service, "syncOmsDispatch", false);
        ReflectionTestUtils.setField(service, "pollExecutionMode", "LIVE");

        assertFalse(service.canRouteExecutionMode("LIVE"));
    }
}
