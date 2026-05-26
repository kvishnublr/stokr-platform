package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replays a historical trading session through registered catalog strategies and persists
 * results as real signals (no backtestRunId). All strategies are resolved via StrategyRegistry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalHistoricalReplayService {

    private final StrategyUniverseResolverService resolverService;
    private final MarketDataQueryService marketDataQueryService;
    private final StrategySignalPipelineService pipelineService;
    private final StrategyRegistry strategyRegistry;

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

    public ReplayResult replay(String strategyKey, LocalDate from, LocalDate to) {
        if (from == null) from = LocalDate.now(zone);
        if (to == null) to = from;
        if (to.isBefore(from)) to = from;

        TradingStrategy strategy = strategyRegistry.get(strategyKey);
        if (strategy == null) {
            log.warn("replay.strategy_not_registered key={}", strategyKey);
            return new ReplayResult(strategyKey, from, to, 0, 0, 0);
        }

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
                    List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(symbol, "1m", sessionOpen, sessionClose);
                    if (bars.isEmpty()) {
                        bars = marketDataQueryService.rangeAsc(symbol, "5m", sessionOpen, sessionClose);
                    }
                    if (bars.isEmpty()) continue;

                    totalBars += bars.size();
                    for (MarketdataCandle bar : bars) {
                        try {
                            StrategyContext ctx = new StrategyContext(symbol, bar.getOpenTime(), Map.of(),
                                    bar.getClosePrice() != null ? bar.getClosePrice() : BigDecimal.ZERO);
                            StrategySignal signal = strategy.evaluate(ctx);
                            if (signal != null && signal.type() != SignalType.HOLD) {
                                StrategySignalEntity entity = toEntity(strategyKey, symbol, signal, bar.getOpenTime());
                                pipelineService.persistAndDispatch(
                                        entity, UUID.randomUUID().toString(), executionMode, SignalProvenance.REPLAY);
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

    private StrategySignalEntity toEntity(String strategyKey, String symbol, StrategySignal signal, Instant barTime) {
        return StrategySignalEntityMapper.baseEntity(
                signal, strategyKey, symbol, barTime, systemUserId, executionMode, "2.0.0");
    }
}
