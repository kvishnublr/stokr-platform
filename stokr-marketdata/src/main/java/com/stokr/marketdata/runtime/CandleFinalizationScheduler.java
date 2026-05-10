package com.stokr.marketdata.runtime;

import com.stokr.marketdata.service.CandleFinalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandleFinalizationScheduler {

    private final CandleFinalizationService candleFinalizationService;

    @Scheduled(fixedDelayString = "${stokr.marketdata.partial-evict-ms:300000}")
    public void evictStalePartials() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        candleFinalizationService.evictPartialStateOlderThan(cutoff);
        log.debug("marketdata.partial.evicted_before {}", cutoff);
    }
}
