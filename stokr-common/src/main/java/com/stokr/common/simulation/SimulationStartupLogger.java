package com.stokr.common.simulation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class SimulationStartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    private final SimulationRuntimeControlService runtimeControl;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SimulationStartupLogger.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (runtimeControl.isRuntimeEnabled()) {
            log.warn("SIMULATION MODE ENABLED — runtime toggle is ON; live broker orders are hard-blocked");
        } else {
            log.info("SIMULATION MODE DISABLED");
        }
    }
}
