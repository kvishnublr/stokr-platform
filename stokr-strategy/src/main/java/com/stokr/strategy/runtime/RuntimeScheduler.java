package com.stokr.strategy.runtime;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class RuntimeScheduler {

    private final StrategyInstanceRepository instanceRepository;

    @Transactional
    public <T> T withInstanceLock(UUID instanceId, Supplier<T> action) {
        StrategyInstance locked = instanceRepository.lockById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("strategy instance not found"));
        if (locked.isDeleted()) {
            throw new IllegalStateException("strategy instance deleted");
        }
        return action.get();
    }

    @Transactional
    public void withInstanceLock(UUID instanceId, Runnable action) {
        withInstanceLock(instanceId, () -> {
            action.run();
            return null;
        });
    }
}
