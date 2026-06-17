package com.stokr.engine;

import com.stokr.marketdata.MarketDataService;
import com.stokr.marketdata.Universe;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessor {

    private final StrategyService strategyService;
    private final MarketDataService marketDataService;
    private final EntryManager entryManager;
    private final SignalRepository signalRepository;
    private final Universe universe;

    /**
     * Process a single deployment: evaluate strategy against market data, generate signal.
     */
    public void processDeployment(Deployment deployment) {
        try {
            // Get strategy
            Strategy strategy = strategyService.getStrategy(deployment.getStrategyId());
            if (!strategy.isEnabled()) return;

            // Get market data for the universe
            for (String symbol : universe.getSymbols()) {
                var candles = marketDataService.getCandles(symbol, "5min", 50);
                if (candles.isEmpty()) continue;

                BigDecimal ltp = marketDataService.getLtp(symbol);
                if (ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

                MarketContext context = new MarketContext(
                        symbol, candles, ltp, null, Map.of());

                Signal signal = strategyService.evaluateSignal(deployment.getStrategyId(), context);
                if (signal != null && signal.isValid()) {
                    log.info("Signal generated: {} {} {} for deployment {}",
                            signal.side(), signal.symbol(), signal.reason(), deployment.getId());

                    // Persist signal
                    SignalEntity entity = SignalEntity.builder()
                            .deploymentId(deployment.getId())
                            .userId(deployment.getUserId())
                            .strategyId(deployment.getStrategyId())
                            .symbol(signal.symbol())
                            .side(SignalEntity.Side.valueOf(signal.side().name()))
                            .entryPrice(signal.entryPrice())
                            .stopLoss(signal.stopLoss())
                            .target(signal.target())
                            .confidence(BigDecimal.valueOf(signal.confidence()))
                            .reason(signal.reason())
                            .status("GENERATED")
                            .build();
                    signalRepository.save(entity);

                    entryManager.processEntrySignal(deployment, signal);
                }
            }
        } catch (Exception e) {
            log.error("Error processing deployment {}", deployment.getId(), e);
        }
    }
}
