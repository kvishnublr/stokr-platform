package com.stokr.bootstrap.trader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DISABLED: ExecutionGuardStreamService depends on removed modules (stokr-execution).
 * This stub is kept to prevent compilation errors.
 * For NSE_SPIKE_DETECTION V2.0, market events are handled through WebSocket and market data services.
 */
@Service
@Slf4j
public class ExecutionGuardStreamService {

    public ExecutionGuardStreamService() {
        log.warn("ExecutionGuardStreamService is disabled (stub only)");
    }
}
