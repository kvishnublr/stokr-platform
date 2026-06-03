package com.stokr.strategy.operational;

import lombok.extern.slf4j.Slf4j;
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

    public StrategyExecutionModeService(
            @Value("${stokr.strategy.execution-modes.GAP_FILL:PAPER}") String gapFill,
            @Value("${stokr.strategy.execution-modes.SECTOR_LAGGARD:DRY_RUN}") String sectorLaggard,
            @Value("${stokr.strategy.execution-modes.NSE_SPIKE_DETECTION:DRY_RUN}") String spike,
            @Value("${stokr.strategy.execution-modes.EARLY_BREAKOUT:DRY_RUN}") String earlyBreakout,
            @Value("${stokr.strategy.execution-modes.VWAP_BOUNCE:DRY_RUN}") String vwapBounce,
            @Value("${stokr.strategy.execution-modes.INDEX_HUNT:DRY_RUN}") String indexHunt,
            @Value("${stokr.strategy.execution-modes.ADV_CASH:LIVE}") String advCash,
            @Value("${stokr.strategy.execution-modes.S3_VWAP_RETEST:DRY_RUN}") String s3,
            @Value("${stokr.strategy.execution-modes.S7_RANGE_FADE:DRY_RUN}") String s7,
            @Value("${stokr.strategy.execution-modes.PRE_OPEN_GAP_OI:PAPER}") String preOpenGapOi,
            @Value("${stokr.strategy.execution-modes.COMMODITIES_E2E_TEST:PAPER}") String commoditiesE2eTest,
            @Value("${stokr.strategy.execution-modes.allow-live:true}") boolean allowLive,
            @Value("${stokr.strategy.execution-modes.live-validated:GAP_FILL,NSE_SPIKE_DETECTION,VWAP_BOUNCE,SECTOR_LAGGARD,ADV_CASH}") String liveValidatedCsv) {
        modes = new LinkedHashMap<>();
        modes.put("GAP_FILL", StrategyExecutionMode.parse(gapFill));
        modes.put("SECTOR_LAGGARD", StrategyExecutionMode.parse(sectorLaggard));
        modes.put("NSE_SPIKE_DETECTION", StrategyExecutionMode.parse(spike));
        modes.put("EARLY_BREAKOUT", StrategyExecutionMode.parse(earlyBreakout));
        modes.put("VWAP_BOUNCE", StrategyExecutionMode.parse(vwapBounce));
        modes.put("INDEX_HUNT", StrategyExecutionMode.parse(indexHunt));
        modes.put("ADV_CASH", StrategyExecutionMode.parse(advCash));
        modes.put("S3_VWAP_RETEST", StrategyExecutionMode.parse(s3));
        modes.put("S7_RANGE_FADE", StrategyExecutionMode.parse(s7));
        modes.put("PRE_OPEN_GAP_OI", StrategyExecutionMode.parse(preOpenGapOi));
        modes.put("COMMODITIES_E2E_TEST", StrategyExecutionMode.parse(commoditiesE2eTest));
        this.allowLive = allowLive;
        this.liveValidated = parseValidatedList(liveValidatedCsv);
    }

    public StrategyExecutionMode modeFor(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return StrategyExecutionMode.PAPER;
        }
        StrategyExecutionMode configured = modes.getOrDefault(
                strategyKey.trim().toUpperCase(Locale.ROOT),
                StrategyExecutionMode.PAPER);
        if (configured == StrategyExecutionMode.LIVE && !isLiveValidated(strategyKey)) {
            log.warn("execution_mode.live_blocked strategyKey={} downgraded=PAPER", strategyKey);
            return StrategyExecutionMode.PAPER;
        }
        return configured;
    }

    public Map<String, String> allModes() {
        Map<String, String> out = new LinkedHashMap<>();
        modes.forEach((k, v) -> out.put(k, modeFor(k).name()));
        return Map.copyOf(out);
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
