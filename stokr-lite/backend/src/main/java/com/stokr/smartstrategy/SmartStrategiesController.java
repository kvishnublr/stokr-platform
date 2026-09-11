package com.stokr.smartstrategy;

import com.stokr.arbitrage.OptionArbAutoExecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/smart-strategies")
public class SmartStrategiesController {

    private static final Logger log = LoggerFactory.getLogger(SmartStrategiesController.class);

    private final RatioButterflyScanner ratioButterflyScanner;
    private final BrokenWingButterflyScanner bwbScanner;
    private final SkewHarvestScanner skewHarvestScanner;
    private final ExpiryThetaCrushScanner thetaCrushScanner;
    private final OptionArbAutoExecService autoExecService;

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    public SmartStrategiesController(RatioButterflyScanner ratioButterflyScanner,
                                      BrokenWingButterflyScanner bwbScanner,
                                      SkewHarvestScanner skewHarvestScanner,
                                      ExpiryThetaCrushScanner thetaCrushScanner,
                                      OptionArbAutoExecService autoExecService) {
        this.ratioButterflyScanner = ratioButterflyScanner;
        this.bwbScanner = bwbScanner;
        this.skewHarvestScanner = skewHarvestScanner;
        this.thetaCrushScanner = thetaCrushScanner;
        this.autoExecService = autoExecService;
    }

    @GetMapping("/ratio-butterfly/scan")
    public ResponseEntity<Map<String, Object>> scanRatioButterfly(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("ratio-butterfly:" + underlying, () -> {
            List<Map<String, Object>> opps = ratioButterflyScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "RATIO_BUTTERFLY", underlying);
        });
    }

    @GetMapping("/broken-wing-butterfly/scan")
    public ResponseEntity<Map<String, Object>> scanBWB(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("bwb:" + underlying, () -> {
            List<Map<String, Object>> opps = bwbScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "BROKEN_WING_BUTTERFLY", underlying);
        });
    }

    @GetMapping("/skew-harvest/scan")
    public ResponseEntity<Map<String, Object>> scanSkewHarvest(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("skew-harvest:" + underlying, () -> {
            List<Map<String, Object>> opps = skewHarvestScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "SKEW_HARVEST", underlying);
        });
    }

    @GetMapping("/theta-crush/scan")
    public ResponseEntity<Map<String, Object>> scanThetaCrush(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("theta-crush:" + underlying, () -> {
            List<Map<String, Object>> opps = thetaCrushScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "EXPIRY_THETA_CRUSH", underlying);
        });
    }

    @GetMapping("/all/scan")
    public ResponseEntity<Map<String, Object>> scanAll(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);

        try {
            resp.put("ratioButterfly", ratioButterflyScanner.scan(underlying));
        } catch (Exception e) { resp.put("ratioButterfly", List.of()); }

        try {
            resp.put("brokenWingButterfly", bwbScanner.scan(underlying));
        } catch (Exception e) { resp.put("brokenWingButterfly", List.of()); }

        try {
            resp.put("skewHarvest", skewHarvestScanner.scan(underlying));
        } catch (Exception e) { resp.put("skewHarvest", List.of()); }

        try {
            resp.put("thetaCrush", thetaCrushScanner.scan(underlying));
        } catch (Exception e) { resp.put("thetaCrush", List.of()); }

        return ResponseEntity.ok(resp);
    }

    private void tryAutoExec(List<Map<String, Object>> opps) {
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); }
            catch (Exception e) { log.debug("Smart strategy auto-exec: {}", e.getMessage()); }
        }
    }

    private Map<String, Object> wrapResponse(List<Map<String, Object>> opps, String strategyType, String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("strategyType", strategyType);
        resp.put("underlying", underlying);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return resp;
    }

    private ResponseEntity<Map<String, Object>> cachedScan(String key, java.util.function.Supplier<Map<String, Object>> fn) {
        CachedResult cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.ts < 15000) {
            return ResponseEntity.ok(cached.data);
        }
        Map<String, Object> result = fn.get();
        cache.put(key, new CachedResult(result, System.currentTimeMillis()));
        return ResponseEntity.ok(result);
    }

    private record CachedResult(Map<String, Object> data, long ts) {}
}
