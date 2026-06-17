package com.stokr.strategy.operational;

import com.stokr.strategy.analytics.StrategyEdgeGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StrategyExecutionModeService {

    private final Map<String, StrategyExecutionMode> modes;
    private final boolean allowLive;
    private final Set<String> liveValidated;
    private final ObjectProvider<StrategyEdgeGateService> edgeGateProvider;

    public StrategyExecutionModeService(
            @Value("${stokr.strategy.execution-modes.VWAP_TRIPLE_CONFIRMATION:BOTH}") String vwapTriple,
            @Value("${stokr.strategy.execution-modes.TRADE_BOOK_IMBALANCE:BOTH}") String tradeBook,
            @Value("${stokr.strategy.execution-modes.PRE_OPEN_GAP_OI:BOTH}") String preOpenGapOi,
            @Value("${stokr.strategy.execution-modes.ORB_V:BOTH}") String orbV,
            @Value("${stokr.strategy.execution-modes.MORNING_SURGE:BOTH}") String morningSurge,
            @Value("${stokr.strategy.execution-modes.allow-live:true}") boolean allowLive,
            @Value("${stokr.strategy.execution-modes.live-validated:VWAP_TRIPLE_CONFIRMATION,TRADE_BOOK_IMBALANCE,PRE_OPEN_GAP_OI,ORB_V,MORNING_SURGE}") String liveValidatedCsv,
            ObjectProvider<StrategyEdgeGateService> edgeGateProvider) {
        modes = new LinkedHashMap<>();
        modes.put("VWAP_TRIPLE_CONFIRMATION", StrategyExecutionMode.parse(vwapTriple));
        modes.put("TRADE_BOOK_IMBALANCE", StrategyExecutionMode.parse(tradeBook));
        modes.put("PRE_OPEN_GAP_OI", StrategyExecutionMode.parse(preOpenGapOi));
        modes.put("ORB_V", StrategyExecutionMode.parse(orbV));
        modes.put("MORNING_SURGE", StrategyExecutionMode.parse(morningSurge));
        this.allowLive = allowLive;
        this.liveValidated = parseValidatedList(liveValidatedCsv);
        this.edgeGateProvider = edgeGateProvider;
    }

    public StrategyExecutionMode modeFor(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return StrategyExecutionMode.PAPER;
        }
        StrategyExecutionMode configured = modes.getOrDefault(
                strategyKey.trim().toUpperCase(Locale.ROOT),
                StrategyExecutionMode.PAPER);
        boolean wantsLiveLeg = configured == StrategyExecutionMode.LIVE
                || configured == StrategyExecutionMode.BOTH;
        if (wantsLiveLeg && !isLiveValidated(strategyKey)) {
            log.warn("execution_mode.live_blocked strategyKey={} downgraded=PAPER", strategyKey);
            return StrategyExecutionMode.PAPER;
        }
        if (wantsLiveLeg && isEdgeDemoted(strategyKey)) {
            log.warn("execution_mode.edge_demoted strategyKey={} downgraded=PAPER", strategyKey);
            return StrategyExecutionMode.PAPER;
        }
        return configured;
    }

    /** Rolling entry-edge gate: strategies below their breakeven target-first rate trade paper-only. */
    private boolean isEdgeDemoted(String strategyKey) {
        if (edgeGateProvider == null) {
            return false;
        }
        try {
            StrategyEdgeGateService gate = edgeGateProvider.getIfAvailable();
            return gate != null && gate.isDemoted(strategyKey);
        } catch (Exception ex) {
            log.debug("execution_mode.edge_gate_unavailable {}", ex.getMessage());
            return false;
        }
    }

    public Map<String, String> allModes() {
        Map<String, String> out = new LinkedHashMap<>();
        modes.forEach((k, v) -> out.put(k, modeFor(k).name()));
        return Map.copyOf(out);
    }

    /** Strategy keys allowed to run LIVE when {@code stokr.strategy.execution-modes.allow-live} is true. */
    public Set<String> liveValidatedStrategyKeys() {
        return Set.copyOf(liveValidated);
    }

    private boolean isLiveValidated(String strategyKey) {
        if (!allowLive) {
            return false;
        }
        if (liveValidated.isEmpty()) {
            return false;
        }
        return liveValidated.contains(strategyKey.trim().toUpperCase(Locale.ROOT));
    }

    private static Set<String> parseValidatedList(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
