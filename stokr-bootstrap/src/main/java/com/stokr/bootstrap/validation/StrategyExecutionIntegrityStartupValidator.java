package com.stokr.bootstrap.validation;

import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutionIntegrityStartupValidator {

    private final StrategyRuntimeBindingRepository bindingRepository;
    private final Environment environment;

    @Value("${stokr.strategy.integrity.fail-on-legacy-schedulers:true}")
    private boolean failOnLegacySchedulers;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        long duplicateBindings = bindingRepository.countDuplicateActiveBindings();
        if (duplicateBindings > 0) {
            String msg = "Duplicate active runtime bindings detected: " + duplicateBindings;
            log.error("startup.integrity.FAIL {}", msg);
            throw new IllegalStateException(msg);
        }

        if (failOnLegacySchedulers && legacySchedulerEnabled()) {
            String msg = "Legacy detector schedulers must remain disabled — use catalog SignalGenerators only";
            log.error("startup.integrity.FAIL {}", msg);
            throw new IllegalStateException(msg);
        }

        log.info("startup.integrity.pass strategy execution single-path validated");
    }

    private boolean legacySchedulerEnabled() {
        return isTrue("stokr.legacy.advcash.scheduler.enabled")
                || isTrue("stokr.legacy.futures.scheduler.enabled")
                || isTrue("stokr.legacy.indexhunt.scheduler.enabled");
    }

    private boolean isTrue(String key) {
        return environment.getProperty(key, Boolean.class, false);
    }
}
