package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.ematrend.EmaTrendFollowingSignalGenerator;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.meanreversion.MeanReversionV2SignalGenerator;
import com.stokr.strategy.momentum.MomentumBreakoutSignalGenerator;
import com.stokr.strategy.openingrange.OpeningRangeBreakoutSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.vwap.VwapMeanReversionSignalGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replays a historical trading session through live-signal generators and persists
 * results as real signals (no backtestRunId). Supports any strategy key and date range.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalHistoricalReplayService {

    private final VwapMeanReversionSignalGenerator vwapGenerator;
    private final MeanReversionSignalGenerator meanReversionGenerator;
    private final MeanReversionV2SignalGenerator meanReversionV2Generator;
    private final MomentumBreakoutSignalGenerator momentumGenerator;
    private final OpeningRangeBreakoutSignalGenerator orbGenerator;
    private final EmaTrendFollowingSignalGenerator emaTrendGenerator;
    private final StrategyUniverseResolverService resolverService;
    private final MarketDataQueryService marketDataQueryService;
    private final StrategySignalPipelineService pipelineService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.strategy.poll-execution-mode:PAPER}")
    private String executionMode;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    public record ReplayResult(String strategyKey, LocalDate from, LocalDate to,
                               int symbolsScanned, int barsProcessed, int signalsGenerated) {}

    /**
     * Replays all bound symbols for a strategy across a date range.
     * Signals are saved as live signals (backtestRunId = null).
     */
    public ReplayResult replay(String strategyKey, LocalDate from, LocalDate to) {
        if (from == null) from = LocalDate.now(zone);
        if (to == null) to = from;
        if (to.isBefore(from)) to = from;

        List<StrategyRuntimeBinding> bindings = resolverService.resolveBindingsForStrategy(strategyKey);
        if (bindings.isEmpty()) {
            log.warn("replay.no_bindings strategyKey={} from={} to={}", strategyKey, from, to);
            return new ReplayResult(strategyKey, from, to, 0, 0, 0);
        }

        Set<String> symbolSet = new LinkedHashSet<>();
        for (StrategyRuntimeBinding b : bindings) {
            for (StrategyUniverseSymbol sym : resolverService.resolveSymbolEntitiesForGroup(b.getUniverseGroup().getId())) {
                symbolSet.add(sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol());
            }
        }
        List<String> symbols = new ArrayList<>(symbolSet);

        log.info("replay.start strategyKey={} from={} to={} symbols={}", strategyKey, from, to, symbols.size());

        int totalBars = 0, totalSignals = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant sessionOpen  = date.atTime(sessionStart).atZone(zone).toInstant();
            Instant sessionClose = date.atTime(sessionEnd).atZone(zone).toInstant();

            for (String symbol : symbols) {
                try {
                    List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(symbol, "5m", sessionOpen, sessionClose);
                    if (bars.isEmpty()) bars = marketDataQueryService.rangeAsc(symbol, "1m", sessionOpen, sessionClose);
                    if (bars.isEmpty()) continue;

                    totalBars += bars.size();
                    for (MarketdataCandle bar : bars) {
                        try {
                            StrategySignalEntity sig = evaluate(strategyKey, symbol, bar.getOpenTime());
                            if (sig != null) {
                                pipelineService.persistAndDispatch(sig, UUID.randomUUID().toString(), executionMode);
                                totalSignals++;
                            }
                        } catch (Exception ex) {
                            log.debug("replay.bar_error symbol={} bar={} {}", symbol, bar.getOpenTime(), ex.getMessage());
                        }
                    }
                } catch (Exception ex) {
                    log.warn("replay.symbol_error strategyKey={} symbol={} {}", strategyKey, symbol, ex.getMessage());
                }
            }
        }

        log.info("replay.done strategyKey={} from={} to={} symbols={} bars={} signals={}",
                strategyKey, from, to, symbols.size(), totalBars, totalSignals);
        return new ReplayResult(strategyKey, from, to, symbols.size(), totalBars, totalSignals);
    }

    private StrategySignalEntity evaluate(String strategyKey, String symbol, Instant barTime) {
        return switch (strategyKey) {
            case "VWAP_MEAN_REVERSION"     -> vwapGenerator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime, "5m");
            case "MEAN_REVERSION"          -> meanReversionGenerator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime);
            case "MEAN_REVERSION_V2"       -> meanReversionV2Generator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime);
            case "MOMENTUM_BREAKOUT"       -> momentumGenerator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime, "5m");
            case "OPENING_RANGE_BREAKOUT"  -> orbGenerator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime, "5m");
            case "EMA_TREND_FOLLOW"        -> emaTrendGenerator.evaluatePersistableAtOpen(symbol, systemUserId, null, executionMode, barTime, "5m");
            default -> {
                log.warn("replay.unknown_strategy key={}", strategyKey);
                yield null;
            }
        };
    }
}
