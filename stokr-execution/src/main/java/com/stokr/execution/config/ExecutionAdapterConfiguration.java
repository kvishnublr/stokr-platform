package com.stokr.execution.config;

import com.stokr.execution.adapter.ExecutionAdapter;
import com.stokr.execution.adapter.LiveExecutionAdapter;
import com.stokr.execution.adapter.PaperExchangeAdapter;
import com.stokr.execution.dispatcher.ExecutionDispatcher;
import com.stokr.execution.dispatcher.ExecutionAdapterRegistry;
import com.stokr.oms.domain.ExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Execution adapter configuration.
 * Wires up adapters for LIVE and PAPER execution modes.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ExecutionAdapterConfiguration {

    private final LiveExecutionAdapter liveExecutionAdapter;
    private final PaperExchangeAdapter paperExchangeAdapter;

    /**
     * Configure execution dispatcher with registered adapters.
     */
    @Bean
    public ExecutionDispatcher executionDispatcher() {
        ExecutionDispatcher dispatcher = new ExecutionDispatcher();

        // Register LIVE adapter
        dispatcher.registerAdapter(ExecutionMode.LIVE, liveExecutionAdapter);
        log.info("execution.config.registered mode=LIVE vendor={}", liveExecutionAdapter.vendorCode());

        // Register PAPER adapter
        dispatcher.registerAdapter(ExecutionMode.PAPER, paperExchangeAdapter);
        log.info("execution.config.registered mode=PAPER vendor={}", paperExchangeAdapter.vendorCode());

        // Register SIMULATED adapter (uses paper for now)
        dispatcher.registerAdapter(ExecutionMode.SIMULATED, paperExchangeAdapter);
        log.info("execution.config.registered mode=SIMULATED vendor={}", paperExchangeAdapter.vendorCode());

        // TODO: PHASE 2 - Register HYBRID adapter
        // dispatcher.registerAdapter(ExecutionMode.BOTH, hybridExecutionAdapter);

        return dispatcher;
    }
}
