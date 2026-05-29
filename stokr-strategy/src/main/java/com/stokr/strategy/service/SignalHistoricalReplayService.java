package com.stokr.strategy.service;

import com.stokr.common.exception.BadRequestException;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.seed.ReplayEquityCandleSeedService;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final String REPLAY_EXECUTION_MODE = "SIMULATED";
    private static final long MIN_BARS_FOR_REPLAY = 25L;

    private final StrategyUniverseResolverService resolverService;
    private final MarketDataQueryService marketDataQueryService;
    private final StrategySignalPipelineService pipelineService;
    private final StrategyRegistry strategyRegistry;
    private final ReplayEquityCandleSeedService replayEquityCandleSeedService;
    private final ConfidenceEngineV2 confidenceEngineV2;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Value("${stokr.signal.replay.auto-seed-candles:true}")
    private boolean autoSeedCandles;

    public record ReplayResult(
            String strategyKey,
            LocalDate from,
            LocalDate to,
            int symbolsScanned,
            int barsProcessed,
            int signalsGenerated,
            int symbolsSeeded
    ) {
    }

    public record ReplayPreflight(
            String strategyKey,
            LocalDate from,
            LocalDate to,
            boolean ready,
            int symbolCount,
            int symbolsWithData,
            int symbolsNeedingSeed,
            int estimatedBars,
            List<String> blockers,
            List<String> warnings
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyKey", strategyKey);
            m.put("from", from != null ? from.toString() : null);
            m.put("to", to != null ? to.toString() : null);
            m.put("ready", ready);
            m.put("symbolCount", symbolCount);
            m.put("symbolsWithData", symbolsWithData);
            m.put("symbolsNeedingSeed", symbolsNeedingSeed);
            m.put("estimatedBars", estimatedBars);
            m.put("blockers", blockers);
            m.put("warnings", warnings);
            return m;
        }
    }

    public ReplayPreflight preflight(String strategyKey, LocalDate from, LocalDate to) {
        LocalDate[] range = normalizeRange(from, to);
        from = range[0];
        to = range[1];

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (strategyRegistry.get(strategyKey) == null) {
            blockers.add("Strategy not registered in runtime: " + strategyKey);
        }

        List<String> symbols = resolveSymbols(strategyKey);
        if (symbols.isEmpty()) {
            blockers.add("No runtime universe bindings for " + strategyKey + ". Configure bindings in Admin → Runtime Bindings.");
        }

        int symbolsWithData = 0;
        int symbolsNeedingSeed = 0;
        int estimatedBars = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                continue;
            }
            Instant sessionOpen = date.atTime(sessionStart).atZone(zone).toInstant();
            Instant sessionClose = date.atTime(sessionEnd).atZone(zone).toInstant();
            for (String symbol : symbols) {
                long barCount = marketDataQueryService.rangeCount(symbol, "1m", sessionOpen, sessionClose);
                if (barCount >= MIN_BARS_FOR_REPLAY) {
                    symbolsWithData++;
                    estimatedBars += (int) barCount;
                } else {
                    symbolsNeedingSeed++;
                }
            }
        }

        if (!symbols.isEmpty() && symbolsWithData == 0) {
            if (autoSeedCandles) {
                warnings.add("No replay-grade 1m candles found — synthetic seed will run before replay.");
            } else {
                blockers.add("No 1m candle history for selected dates. Run Admin → Backfill or POST /api/admin/signals/seed-replay-candles.");
            }
        } else if (symbolsNeedingSeed > 0 && autoSeedCandles) {
            warnings.add(symbolsNeedingSeed + " symbol-day(s) lack sufficient 1m bars — seed will attempt to fill gaps.");
        }

        boolean ready = blockers.isEmpty();
        return new ReplayPreflight(strategyKey, from, to, ready, symbols.size(), symbolsWithData, symbolsNeedingSeed, estimatedBars, blockers, warnings);
    }

    public ReplayResult replay(String strategyKey, LocalDate from, LocalDate to) {
        ReplayPreflight preflight = preflight(strategyKey, from, to);
        if (!preflight.ready()) {
            throw new BadRequestException(String.join(" ", preflight.blockers()));
        }

        from = preflight.from();
        to = preflight.to();

        TradingStrategy strategy = strategyRegistry.get(strategyKey);
        List<String> symbols = resolveSymbols(strategyKey);

        int symbolsSeeded = 0;
        if (autoSeedCandles && (preflight.symbolsWithData() == 0 || preflight.symbolsNeedingSeed() > 0)) {
            Map<String, Object> seeded = replayEquityCandleSeedService.seedSymbols(symbols);
            symbolsSeeded = ((Number) seeded.getOrDefault("seeded", 0)).intValue();
            log.info("replay.seed_done strategyKey={} seeded={} skipped={}", strategyKey, seeded.get("seeded"), seeded.get("skipped"));
        }

        log.info("replay.start strategyKey={} from={} to={} symbols={}", strategyKey, from, to, symbols.size());

        int totalBars = 0;
        int totalSignals = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                continue;
            }
            Instant sessionOpen = date.atTime(sessionStart).atZone(zone).toInstant();
            Instant sessionClose = date.atTime(sessionEnd).atZone(zone).toInstant();

            for (String symbol : symbols) {
                try {
                    List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(symbol, "1m", sessionOpen, sessionClose);
                    if (bars.isEmpty()) {
                        bars = marketDataQueryService.rangeAsc(symbol, "5m", sessionOpen, sessionClose);
                    }
                    if (bars.isEmpty()) {
                        continue;
                    }

                    totalBars += bars.size();
                    for (MarketdataCandle bar : bars) {
                        try {
                            StrategyContext ctx = new StrategyContext(symbol, bar.getOpenTime(), Map.of(),
                                    bar.getClosePrice() != null ? bar.getClosePrice() : BigDecimal.ZERO);
                            StrategySignal signal = strategy.evaluate(ctx);
                            if (signal != null && signal.type() != SignalType.HOLD) {
                                StrategySignal scored = confidenceEngineV2.enrich(signal, strategyKey, symbol, bar.getOpenTime());
                                StrategySignalEntity entity = toEntity(strategyKey, symbol, scored, bar.getOpenTime());
                                StrategySignalEntity saved = pipelineService.persistAndDispatch(
                                        entity, UUID.randomUUID().toString(), REPLAY_EXECUTION_MODE, SignalProvenance.REPLAY);
                                if (saved != null) {
                                    totalSignals++;
                                }
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

        log.info("replay.done strategyKey={} from={} to={} symbols={} bars={} signals={} seeded={}",
                strategyKey, from, to, symbols.size(), totalBars, totalSignals, symbolsSeeded);
        return new ReplayResult(strategyKey, from, to, symbols.size(), totalBars, totalSignals, symbolsSeeded);
    }

    private List<String> resolveSymbols(String strategyKey) {
        List<StrategyRuntimeBinding> bindings = resolverService.resolveBindingsForStrategy(strategyKey);
        Set<String> symbolSet = new LinkedHashSet<>();
        for (StrategyRuntimeBinding b : bindings) {
            for (StrategyUniverseSymbol sym : resolverService.resolveSymbolEntitiesForGroup(b.getUniverseGroup().getId())) {
                String s = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                if (s != null && !s.isBlank()) {
                    symbolSet.add(s.trim().toUpperCase());
                }
            }
        }
        return new ArrayList<>(symbolSet);
    }

    private LocalDate[] normalizeRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(zone);
        if (from == null) {
            from = today;
        }
        if (to == null) {
            to = from;
        }
        if (to.isBefore(from)) {
            to = from;
        }
        if (from.isAfter(today)) {
            throw new BadRequestException("Replay from date cannot be in the future (IST). Latest: " + today);
        }
        if (to.isAfter(today)) {
            throw new BadRequestException("Replay to date cannot be in the future (IST). Latest: " + today);
        }
        return new LocalDate[] { from, to };
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private StrategySignalEntity toEntity(String strategyKey, String symbol, StrategySignal signal, Instant barTime) {
        StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(
                signal, strategyKey, symbol, barTime, systemUserId, REPLAY_EXECUTION_MODE, "2.0.0");
        StrategySignalEntityMapper.applyStreamMetadata(entity, SignalOwnerType.SYSTEM, "PENDING");
        return entity;
    }
}
