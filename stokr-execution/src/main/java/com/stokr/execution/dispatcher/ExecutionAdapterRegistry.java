package com.stokr.execution.dispatcher;

import com.stokr.execution.adapter.ExecutionAdapter;
import com.stokr.oms.domain.ExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for execution adapters by execution mode.
 * Maps ExecutionMode to adapter implementations.
 */
@org.springframework.stereotype.Component
@Slf4j
public class ExecutionAdapterRegistry {

    private final Map<ExecutionMode, ExecutionAdapter> byMode = new ConcurrentHashMap<>();

    /**
     * Register an adapter for a specific execution mode.
     *
     * @param mode Execution mode
     * @param adapter Adapter implementation
     */
    public void register(ExecutionMode mode, ExecutionAdapter adapter) {
        byMode.put(mode, adapter);
        log.info("executor.adapter.registry.registered mode={} vendor={}", mode, adapter.vendorCode());
    }

    /**
     * Get adapter for mode.
     *
     * @param mode Execution mode
     * @return Adapter implementation
     * @throws IllegalArgumentException if mode not registered
     */
    public ExecutionAdapter get(ExecutionMode mode) {
        ExecutionAdapter adapter = byMode.get(mode);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for mode: " + mode);
        }
        return adapter;
    }

    /**
     * Get adapter for mode, or null if not registered.
     *
     * @param mode Execution mode
     * @return Adapter implementation or null
     */
    public ExecutionAdapter getOrNull(ExecutionMode mode) {
        return byMode.get(mode);
    }

    /**
     * Check if adapter is registered for mode.
     *
     * @param mode Execution mode
     * @return true if registered
     */
    public boolean isRegistered(ExecutionMode mode) {
        return byMode.containsKey(mode);
    }

    /**
     * List all registered modes.
     *
     * @return List of modes with registered adapters
     */
    public List<ExecutionMode> listRegisteredModes() {
        return List.copyOf(byMode.keySet());
    }
}
