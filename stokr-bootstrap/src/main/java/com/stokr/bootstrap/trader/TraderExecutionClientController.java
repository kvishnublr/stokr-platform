package com.stokr.bootstrap.trader;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DISABLED: TraderExecutionClientController depends on removed modules (stokr-execution).
 * This stub is kept to prevent compilation errors.
 * For NSE_SPIKE_DETECTION V2.0, execution is handled through OMS and Broker adapters.
 */
@RestController
@RequestMapping("/api/trader")
@Tag(name = "Trader execution client")
@Slf4j
public class TraderExecutionClientController {

    public TraderExecutionClientController() {
        log.warn("TraderExecutionClientController is disabled (stub only)");
    }
}
