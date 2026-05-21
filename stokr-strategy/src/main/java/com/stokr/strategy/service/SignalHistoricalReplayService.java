package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
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
import java.util.List;
import java.util.UUID;

/**
 * Replays a historical trading session through live-signal generators and persists
 * the results as real signals (no backtestRunId). Used to backfill today's signals
 * when the scanner ran on old code or was restarted after market hours.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalHistoricalReplayService {

    private final VwapMeanReversionSignalGenerator vwapGenerator;
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

    /**
     * Replays the given date's full session through the VWAP strategy for all bound universe symbols.
     * Saves qualifying signals as live signals (backtestRunId = null).
     *
     * @param date date to replay (defaults to today if null)
     * @return number of signals generated
     */
    public int replayVwapForDate(LocalDate date) {
        if (date == null) date = LocalDate.now(zone);

        Instant sessionOpen  = date.atTime(sessionStart).atZone(zone).toInstant();
        Instant sessionClose = date.atTime(sessionEnd).atZone(zone).toInstant();

        List<StrategyRuntimeBinding> bindings = resolverService.resolveBindingsForStrategy("VWAP_MEAN_REVERSION");
        if (bindings.isEmpty()) {
            log.warn("replay.vwap.no_bindings date={}", date);
            return 0;
        }

        List<String> symbols = new ArrayList<>();
        for (StrategyRuntimeBinding b : bindings) {
            for (StrategyUniverseSymbol sym : resolverService.resolveSymbolEntitiesForGroup(b.getUniverseGroup().getId())) {
                String s = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                if (!symbols.contains(s)) symbols.add(s);
            }
        }

        log.info("replay.vwap.start date={} symbols={}", date, symbols.size());

        int signalCount = 0;
        int barCount    = 0;

        for (String symbol : symbols) {
            try {
                // Fetch all 5m bars for this symbol on the given date
                List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(symbol, "5m", sessionOpen, sessionClose);
                if (bars.isEmpty()) {
                    // fallback to 1m
                    bars = marketDataQueryService.rangeAsc(symbol, "1m", sessionOpen, sessionClose);
                }
                if (bars.isEmpty()) continue;

                barCount += bars.size();

                for (MarketdataCandle bar : bars) {
                    try {
                        StrategySignalEntity sig = vwapGenerator.evaluatePersistableAtOpen(
                                symbol, systemUserId, null, executionMode,
                                bar.getOpenTime(), "5m");
                        if (sig != null) {
                            pipelineService.persistAndDispatch(sig, UUID.randomUUID().toString(), executionMode);
                            signalCount++;
                        }
                    } catch (Exception ex) {
                        log.debug("replay.vwap.bar_error symbol={} bar={} {}", symbol, bar.getOpenTime(), ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.warn("replay.vwap.symbol_error symbol={} {}", symbol, ex.getMessage());
            }
        }

        log.info("replay.vwap.done date={} symbols={} bars={} signals={}", date, symbols.size(), barCount, signalCount);
        return signalCount;
    }
}
