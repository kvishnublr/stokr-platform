package com.stokr.bootstrap.config;

import com.stokr.strategy.runtime.SignalPipelineActivationService;
import com.stokr.strategy.runtime.StrategyEvaluationScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<StrategyEvaluationScheduler> evaluationScheduler;

    @Value("${stokr.strategy.pipeline.auto-activate:true}")
    private boolean autoActivate;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoActivate) {
            return;
        }
        try {
            // V51 migration already enables catalog/bindings; skip bulk universe sync on boot (avoids TX abort).
            var summary = activationService.activate(false, false);
            log.info("signal.pipeline.startup_activation {}", summary);
            evaluationScheduler.ifAvailable(StrategyEvaluationScheduler::poll);
        } catch (Exception ex) {
            log.error("signal.pipeline.startup_activation_failed {}", ex.getMessage(), ex);
        }
    }
}
