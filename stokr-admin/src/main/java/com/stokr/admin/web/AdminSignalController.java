package com.stokr.admin.web;

import com.stokr.admin.signal.AdminSignalDetailDto;
import com.stokr.admin.signal.AdminSignalDto;
import com.stokr.admin.signal.AdminSignalParams;
import com.stokr.admin.signal.AdminSignalQuantValidationDto;
import com.stokr.admin.signal.AdminSignalQuantValidationService;
import com.stokr.admin.signal.AdminSignalQueryService;
import com.stokr.admin.signal.AdminSignalStatsDto;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.api.ApiResponse;
import com.stokr.risk.service.LiveTradingArmingService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.marketdata.seed.ReplayEquityCandleSeedService;
import com.stokr.strategy.catalog.CatalogDrivenScanScheduler;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.runtime.SignalPipelineActivationService;
import com.stokr.strategy.runtime.StrategyEvaluationScheduler;
import com.stokr.strategy.service.SignalHistoricalReplayService;
import com.stokr.strategy.service.SignalOutcomeTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/admin/signals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Signal monitor")
@Slf4j
public class AdminSignalController {

    private final AdminSignalQueryService queryService;
    private final AdminSignalQuantValidationService quantValidationService;
    private final LiveTradingArmingService liveTradingArmingService;
    private final AuthUserRepository authUserRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyInstanceLifecycleService strategyInstanceLifecycleService;
    private final SignalOutcomeTrackerService outcomeTrackerService;
    private final SignalHistoricalReplayService historicalReplayService;
    private final SignalPipelineActivationService signalPipelineActivationService;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final StrategyToggleService strategyToggleService;
    private final ObjectProvider<StrategyEvaluationScheduler> strategyEvaluationScheduler;
    private final ObjectProvider<CatalogDrivenScanScheduler> catalogDrivenScanScheduler;
    private final ReplayEquityCandleSeedService replayEquityCandleSeedService;
    private final StrategyUniverseResolverService universeResolverService;

    private static final List<String> REPLAY_SEED_GROUP_KEYS = List.of("NIFTY_50", "NIFTY_100");
    private static final List<String> REPLAY_SEED_EXTRA_SYMBOLS = List.of("NIFTY_FUT");

    @GetMapping("/stats")
    @Operation(summary = "Aggregate signal stats for today or custom window")
    public ApiResponse<AdminSignalStatsDto> stats(
            @RequestParam(required = false) Instant since
    ) {
        Instant from = since != null ? since
                : Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
        return ApiResponse.ok(queryService.stats(from), CorrelationIdHolder.get());
    }

    @GetMapping("/quant-validation")
    @Operation(summary = "Quant sanity checks on production signal analytics (replay isolation, sample size)")
    public ApiResponse<AdminSignalQuantValidationDto> quantValidation(
            @RequestParam(required = false) Instant since
    ) {
        Instant from = since != null ? since
                : Instant.now().atZone(ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
        AdminSignalStatsDto stats = queryService.stats(from);
        return ApiResponse.ok(quantValidationService.validate(stats, from), CorrelationIdHolder.get());
    }

    @GetMapping
    @Operation(summary = "Paginated signal list with optional filters")
    public ApiResponse<PageResponse<AdminSignalDto>> signals(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String strategyName,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String signalType,
            @RequestParam(required = false) String pipeline,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String outcomeStatus,
            @RequestParam(defaultValue = "false") boolean includeTestTrades,
            @RequestParam(defaultValue = "false") boolean includeReplayAndLab
    ) {
        var page = queryService.pageSignals(
                new AdminSignalParams(strategyName, symbol, signalType, pipeline, userId, from, to,
                        outcomeStatus, includeTestTrades, includeReplayAndLab),
                pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Signal detail with linked orders and execution timeline")
    public ApiResponse<AdminSignalDetailDto> detail(@PathVariable UUID id) {
        return ApiResponse.ok(queryService.detail(id), CorrelationIdHolder.get());
    }

    @PostMapping("/activate-live-single")
    @Operation(summary = "LIVE testing: any strategy/symbol may signal; max one open stock (optional ITC+single-strategy legacy mode)")
    public ApiResponse<Map<String, Object>> activateLiveSingle(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "ALL") String strategyKey,
            @RequestParam(defaultValue = "vishnualgo") String traderUsername,
            @RequestParam(defaultValue = "true") boolean armLive,
            @RequestParam(defaultValue = "true") boolean runImmediatePoll
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (armLive) {
            liveTradingArmingService.setArmed(true);
            out.put("liveTradingArmed", true);
        }

        boolean legacySingleStock = symbol != null && !symbol.isBlank()
                && strategyKey != null && !strategyKey.isBlank() && !"ALL".equalsIgnoreCase(strategyKey.trim());
        if (legacySingleStock) {
            out.putAll(signalPipelineActivationService.activateLiveSingleSymbol(symbol, strategyKey));
        } else {
            out.putAll(signalPipelineActivationService.activateLiveTestingPilot());
        }

        UUID traderId = authUserRepository.findByUsernameIgnoreCaseAndDeletedFalse(traderUsername.trim())
                .map(u -> u.getId())
                .orElse(null);
        out.put("traderUsername", traderUsername);
        out.put("traderUserId", traderId != null ? traderId.toString() : null);

        int instancesStarted = 0;
        int instancesCreated = 0;
        if (traderId != null) {
            List<StrategyDefinition> targets = strategyDefinitionRepository.findAll().stream()
                    .filter(d -> !d.isDeleted() && d.isEnabled())
                    .filter(d -> {
                        if (legacySingleStock) {
                            return strategyKey.trim().equalsIgnoreCase(d.getStrategyKey());
                        }
                        String k = d.getStrategyKey() != null ? d.getStrategyKey().toUpperCase() : "";
                        return !k.contains("MCX") && !k.contains("COMMODIT")
                                && !"BREAKOUT_COMMODITIES".equalsIgnoreCase(k);
                    })
                    .toList();
            for (StrategyDefinition def : targets) {
                StrategyInstance inst = strategyInstanceRepository
                        .findByUserIdAndDefinition_IdAndDeletedFalse(traderId, def.getId())
                        .orElse(null);
                if (inst == null) {
                    inst = new StrategyInstance();
                    inst.setDefinition(def);
                    inst.setUserId(traderId);
                    inst.setSymbol(resolvePilotSymbol(def.getStrategyKey(), symbol));
                    inst.setEnabled(true);
                    inst.setExecutionMode("LIVE");
                    inst.setRuntimeState("STOPPED");
                    inst.setRiskMultiplier(java.math.BigDecimal.ONE);
                    inst = strategyInstanceRepository.save(inst);
                    instancesCreated++;
                }
                inst.setExecutionMode("LIVE");
                inst.setEnabled(true);
                strategyInstanceRepository.save(inst);
                if (!"RUNNING".equalsIgnoreCase(inst.getRuntimeState())) {
                    try {
                        strategyInstanceLifecycleService.start(traderId, inst.getId());
                        instancesStarted++;
                    } catch (Exception ex) {
                        log.warn("activate-live.trader_start_failed strategy={} {}", def.getStrategyKey(), ex.getMessage());
                    }
                }
            }
            out.put("traderInstancesCreated", instancesCreated);
            out.put("traderInstancesStarted", instancesStarted);
            out.put("traderStrategiesTargeted", targets.size());
        }

        boolean allStrategies = !legacySingleStock;
        for (var def : strategyDefinitionRepository.findAll().stream().filter(d -> !d.isDeleted()).toList()) {
            if (allStrategies) {
                strategyToggleService.setEnabled(def.getStrategyKey(), true);
            } else {
                strategyToggleService.setEnabled(def.getStrategyKey(),
                        strategyKey.trim().equalsIgnoreCase(def.getStrategyKey()));
            }
        }
        out.put("redisToggles", allStrategies ? "all strategies enabled" : "only " + strategyKey);

        if (runImmediatePoll) {
            strategyEvaluationScheduler.ifAvailable(StrategyEvaluationScheduler::poll);
            catalogDrivenScanScheduler.ifAvailable(CatalogDrivenScanScheduler::scan);
            out.put("immediatePoll", strategyEvaluationScheduler.getIfAvailable() != null ? "triggered" : "disabled");
            out.put("catalogScan", catalogDrivenScanScheduler.getIfAvailable() != null ? "triggered" : "disabled");
        }
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }

    private String resolvePilotSymbol(String strategyKey, String symbolHint) {
        if (symbolHint != null && !symbolHint.isBlank()) {
            return symbolHint.trim().toUpperCase();
        }
        try {
            return signalPipelineActivationService.resolveFirstBindingSymbol(strategyKey);
        } catch (Exception ex) {
            return "NIFTY_FUT";
        }
    }

    @PostMapping("/activate-pipeline")
    @Operation(summary = "Enable all strategies, runtime bindings, universe symbols, and run one immediate scanner cycle")
    public ApiResponse<Map<String, Object>> activatePipeline(
            @RequestParam(defaultValue = "true") boolean syncUniverses,
            @RequestParam(defaultValue = "true") boolean runImmediatePoll
    ) {
        Map<String, Object> out = new LinkedHashMap<>(signalPipelineActivationService.activate(syncUniverses, false));
        int redisToggles = 0;
        for (var def : strategyDefinitionRepository.findAll().stream().filter(d -> !d.isDeleted()).toList()) {
            strategyToggleService.setEnabled(def.getStrategyKey(), true);
            redisToggles++;
        }
        out.put("redisTogglesSet", redisToggles);
        if (runImmediatePoll) {
            strategyEvaluationScheduler.ifAvailable(StrategyEvaluationScheduler::poll);
            catalogDrivenScanScheduler.ifAvailable(CatalogDrivenScanScheduler::scan);
            out.put("immediatePoll", strategyEvaluationScheduler.getIfAvailable() != null ? "triggered" : "disabled");
            out.put("catalogScan", catalogDrivenScanScheduler.getIfAvailable() != null ? "triggered" : "disabled");
        }
        CompletableFuture.runAsync(() -> {
            try {
                int processed = outcomeTrackerService.trackAllPending();
                log.info("activate-pipeline.outcome_backfill processed={}", processed);
            } catch (Exception ex) {
                log.warn("activate-pipeline.outcome_backfill_failed {}", ex.getMessage());
            }
        });
        out.put("outcomeBackfill", "triggered_async");
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }

    @PostMapping("/seed-replay-candles")
    @Operation(summary = "Seed sparse 1m synthetic candles for NIFTY_50/NIFTY_100 equities (replay and catalog scan)")
    public ApiResponse<Map<String, Object>> seedReplayCandles() {
        Set<String> symbols = new LinkedHashSet<>(REPLAY_SEED_EXTRA_SYMBOLS);
        for (String groupKey : REPLAY_SEED_GROUP_KEYS) {
            universeResolverService.resolveActiveBindings().stream()
                    .filter(b -> groupKey.equals(b.getUniverseGroup().getGroupKey()))
                    .map(StrategyRuntimeBinding::getUniverseGroup)
                    .distinct()
                    .forEach(g -> {
                        for (StrategyUniverseSymbol sym : universeResolverService.resolveSymbolEntitiesForGroup(g.getId())) {
                            String s = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                            if (s != null && !s.isBlank()) {
                                symbols.add(s.trim().toUpperCase());
                            }
                        }
                    });
        }
        Map<String, Object> seeded = replayEquityCandleSeedService.seedSymbols(new ArrayList<>(symbols));
        return ApiResponse.ok(seeded, CorrelationIdHolder.get());
    }

    @PostMapping("/track-outcomes")
    @Operation(summary = "Immediately backfill outcomes for ALL pending signals (admin trigger)")
    public ApiResponse<Map<String, Object>> trackOutcomes() {
        int updated = outcomeTrackerService.trackAllPending();
        return ApiResponse.ok(Map.of("status", "completed", "processed", updated), CorrelationIdHolder.get());
    }

    @PostMapping("/replay")
    @Operation(summary = "Replay a strategy over a date range and generate live signals (async)")
    public ApiResponse<Map<String, Object>> replay(
            @RequestParam String strategyKey,
            @RequestParam String from,
            @RequestParam String to
    ) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate   = LocalDate.parse(to);
        String correlationId = CorrelationIdHolder.get();
        CompletableFuture.runAsync(() -> {
            try {
                var result = historicalReplayService.replay(strategyKey, fromDate, toDate);
                log.info("replay.async_done strategyKey={} from={} to={} signals={}",
                        result.strategyKey(), result.from(), result.to(), result.signalsGenerated());
            } catch (Exception ex) {
                log.error("replay.async_error strategyKey={} {}", strategyKey, ex.getMessage(), ex);
            }
        });
        return ApiResponse.ok(Map.of(
                "strategyKey", strategyKey,
                "from",        from,
                "to",          to,
                "status",      "STARTED",
                "message",     "Replay running in background. Check Signal Monitor in ~60s."
        ), correlationId);
    }

    @PostMapping("/track-outcomes-async")
    @Operation(summary = "Async backfill outcomes for all pending signals")
    public ApiResponse<Map<String, Object>> trackOutcomesAsync() {
        String correlationId = CorrelationIdHolder.get();
        CompletableFuture.runAsync(() -> {
            try {
                int processed = outcomeTrackerService.trackAllPending();
                log.info("track-outcomes.async_done processed={}", processed);
            } catch (Exception ex) {
                log.error("track-outcomes.async_error {}", ex.getMessage(), ex);
            }
        });
        return ApiResponse.ok(Map.of(
                "status",  "STARTED",
                "message", "Outcome tracking running in background. Refresh Signal Monitor in ~30s."
        ), correlationId);
    }
}
