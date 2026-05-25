package com.stokr.strategy.runtime;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces per-binding {@code scan_interval_seconds} from the database at runtime.
 */
@Service
public class BindingScanThrottleService {

    private final Map<UUID, Instant> lastScanByBinding = new ConcurrentHashMap<>();

    public boolean shouldScan(StrategyRuntimeBinding binding, Instant now) {
        if (binding == null || now == null) {
            return true;
        }
        int intervalSec = Math.max(5, binding.getScanIntervalSeconds());
        Instant last = lastScanByBinding.get(binding.getId());
        if (last != null && last.plusSeconds(intervalSec).isAfter(now)) {
            return false;
        }
        lastScanByBinding.put(binding.getId(), now);
        return true;
    }

    public void invalidate(UUID bindingId) {
        if (bindingId != null) {
            lastScanByBinding.remove(bindingId);
        }
    }
}
