package com.stokr.bootstrap.config;

import com.stokr.strategy.runtime.SignalPipelineActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, enables strategies/bindings/universe symbols so the scanner can emit signals without manual admin steps.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignalPipelineStartupRunner {

    private final SignalPipelineActivationService activationService;

    @Value("${stokr.strategy.pipeline.auto-activate:true}")
    private boolean autoActivate;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoActivate) {
            return;
        }
        try {
            var summary = activationService.activate(false, false);
            log.info("signal.pipeline.startup_activation {}", summary);
        } catch (Exception ex) {
            log.error("signal.pipeline.startup_activation_failed {}", ex.getMessage(), ex);
        }
    }
}
