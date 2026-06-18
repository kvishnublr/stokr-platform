package com.stokr.marketdata.chartink;

import com.stokr.strategy.SignalGenerator;
import com.stokr.strategy.Signal;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.domain.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes Chartink alerts from the alert store and generates signals.
 * Runs every 5 seconds to pick up recent Chartink webhook alerts and
 * convert them to trading signals via strategy generators.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartinkSignalGenerationService {

    private final ChartinkAlertStore alertStore;
    private final StrategySignalRepository signalRepository;
    private final Map<String, SignalGenerator> signalGenerators;

    /**
     * Runs every 5 seconds to process recent Chartink alerts
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void generateSignalsFromChartinkAlerts() {
        try {
            // Get symbols with recent Chartink alerts (last 5 minutes)
            List<String> recentSymbols = alertStore.recentSymbols(Duration.ofMinutes(5));

            if (recentSymbols.isEmpty()) {
                return;
            }

            log.info("chartink.signal_generation.processing symbols={} count={}", recentSymbols, recentSymbols.size());

            for (String symbol : recentSymbols) {
                try {
                    processSymbolForSignalGeneration(symbol);
                } catch (Exception e) {
                    log.error("chartink.signal_generation.error symbol={}", symbol, e);
                }
            }

            // Clean up expired alerts
            alertStore.evictExpired(Duration.ofMinutes(10));

        } catch (Exception e) {
            log.error("chartink.signal_generation.failed", e);
        }
    }

    private void processSymbolForSignalGeneration(String symbol) {
        // Get all available signal generators
        for (SignalGenerator generator : signalGenerators.values()) {
            try {
                // Check if this generator handles this symbol
                if (!isRelevantGenerator(generator, symbol)) {
                    continue;
                }

                // Generate signal using the strategy
                Signal signal = generator.generate(symbol);

                if (signal != null && signal.isValid()) {
                    log.info("chartink.signal_generation.confirmed symbol={} side={} reason={}",
                            symbol, signal.side(), signal.reason());

                    // Store the signal
                    storeSignal(symbol, signal);
                } else {
                    log.debug("chartink.signal_generation.rejected symbol={}", symbol);
                }
            } catch (Exception e) {
                log.warn("chartink.signal_generation.generator_error symbol={} generator={}",
                        symbol, generator.getClass().getSimpleName(), e);
            }
        }
    }

    private boolean isRelevantGenerator(SignalGenerator generator, String symbol) {
        // Check if generator is interested in this symbol
        // Most generators will accept any symbol that has valid market data
        return true;
    }

    private void storeSignal(String symbol, Signal signal) {
        try {
            StrategySignal strategySignal = new StrategySignal();
            strategySignal.setSymbol(symbol);
            strategySignal.setEntryPrice(signal.entryPrice());
            strategySignal.setStopLoss(signal.stopLoss());
            strategySignal.setTarget(signal.target());
            strategySignal.setSide(signal.side());
            strategySignal.setQuantity(signal.quantity());
            strategySignal.setReason(signal.reason());
            strategySignal.setConfidence(signal.confidence());
            strategySignal.setSource("CHARTINK_WEBHOOK");
            strategySignal.setCreatedAt(Instant.now());

            signalRepository.save(strategySignal);

            log.info("chartink.signal_generation.stored symbol={} side={} price={} target={}",
                    symbol, signal.side(), signal.entryPrice(), signal.target());
        } catch (Exception e) {
            log.error("chartink.signal_generation.store_error symbol={}", symbol, e);
        }
    }
}
