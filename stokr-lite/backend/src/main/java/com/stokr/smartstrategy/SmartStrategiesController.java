package com.stokr.smartstrategy;

import com.stokr.arbitrage.OptionArbAutoExecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    private final BoxSpreadArbScanner boxSpreadScanner;
    private final JadeLizardScanner jadeLizardScanner;
    private final CalendarSpreadEdgeScanner calendarSpreadScanner;
    private final OptionArbAutoExecService autoExecService;

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> lastGoodCache = new ConcurrentHashMap<>();

    public SmartStrategiesController(RatioButterflyScanner ratioButterflyScanner,
                                      BrokenWingButterflyScanner bwbScanner,
                                      SkewHarvestScanner skewHarvestScanner,
                                      ExpiryThetaCrushScanner thetaCrushScanner,
                                      BoxSpreadArbScanner boxSpreadScanner,
                                      JadeLizardScanner jadeLizardScanner,
                                      CalendarSpreadEdgeScanner calendarSpreadScanner,
                                      OptionArbAutoExecService autoExecService) {
        this.ratioButterflyScanner = ratioButterflyScanner;
        this.bwbScanner = bwbScanner;
        this.skewHarvestScanner = skewHarvestScanner;
        this.thetaCrushScanner = thetaCrushScanner;
        this.boxSpreadScanner = boxSpreadScanner;
        this.jadeLizardScanner = jadeLizardScanner;
        this.calendarSpreadScanner = calendarSpreadScanner;
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

    @GetMapping("/box-spread/scan")
    public ResponseEntity<Map<String, Object>> scanBoxSpread(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("box-spread:" + underlying, () -> {
            List<Map<String, Object>> opps = boxSpreadScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "BOX_SPREAD_ARB", underlying);
        });
    }

    @GetMapping("/jade-lizard/scan")
    public ResponseEntity<Map<String, Object>> scanJadeLizard(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("jade-lizard:" + underlying, () -> {
            List<Map<String, Object>> opps = jadeLizardScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "JADE_LIZARD", underlying);
        });
    }

    @GetMapping("/calendar-spread/scan")
    public ResponseEntity<Map<String, Object>> scanCalendarSpread(@RequestParam(defaultValue = "ALL") String underlying) {
        return cachedScan("calendar-spread:" + underlying, () -> {
            List<Map<String, Object>> opps = calendarSpreadScanner.scan(underlying);
            tryAutoExec(opps);
            return wrapResponse(opps, "CALENDAR_SPREAD_EDGE", underlying);
        });
    }

    @GetMapping("/all/scan")
    public ResponseEntity<Map<String, Object>> scanAll(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketOpen", isMarketOpen());
        resp.put("lastScannedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("hh:mm:ss a")));

        try { resp.put("ratioButterfly", ratioButterflyScanner.scan(underlying)); }
        catch (Exception e) { resp.put("ratioButterfly", List.of()); }
        try { resp.put("brokenWingButterfly", bwbScanner.scan(underlying)); }
        catch (Exception e) { resp.put("brokenWingButterfly", List.of()); }
        try { resp.put("skewHarvest", skewHarvestScanner.scan(underlying)); }
        catch (Exception e) { resp.put("skewHarvest", List.of()); }
        try { resp.put("thetaCrush", thetaCrushScanner.scan(underlying)); }
        catch (Exception e) { resp.put("thetaCrush", List.of()); }
        try { resp.put("boxSpread", boxSpreadScanner.scan(underlying)); }
        catch (Exception e) { resp.put("boxSpread", List.of()); }
        try { resp.put("jadeLizard", jadeLizardScanner.scan(underlying)); }
        catch (Exception e) { resp.put("jadeLizard", List.of()); }
        try { resp.put("calendarSpread", calendarSpreadScanner.scan(underlying)); }
        catch (Exception e) { resp.put("calendarSpread", List.of()); }

        return ResponseEntity.ok(resp);
    }

    private void tryAutoExec(List<Map<String, Object>> opps) {
        if (opps == null || opps.isEmpty()) return;
        List<Map<String, Object>> actionable = opps.stream()
            .filter(o -> o.containsKey("legList") && o.get("legList") != null)
            .filter(o -> !"PRE_EXPIRY_SETUP".equals(o.get("subType")))
            .toList();
        if (!actionable.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(actionable); }
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
        resp.put("marketOpen", isMarketOpen());
        resp.put("lastScannedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
        return resp;
    }

    private ResponseEntity<Map<String, Object>> cachedScan(String key, java.util.function.Supplier<Map<String, Object>> fn) {
        CachedResult cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.ts < 15000) {
            return ResponseEntity.ok(cached.data);
        }
        try {
            Map<String, Object> result = fn.get();
            cache.put(key, new CachedResult(result, System.currentTimeMillis()));
            @SuppressWarnings("unchecked")
            List<?> opps = (List<?>) result.get("opportunities");
            log.info("Scan [{}]: {} opportunities found", key, opps != null ? opps.size() : 0);
            if (opps != null && !opps.isEmpty()) {
                lastGoodCache.put(key, result);
            }
            if ((opps == null || opps.isEmpty()) && lastGoodCache.containsKey(key)) {
                Map<String, Object> stale = new LinkedHashMap<>(lastGoodCache.get(key));
                stale.put("stale", true);
                stale.put("staleReason", "No fresh data — showing last known opportunities (LTP based)");
                stale.put("lastScannedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
                stale.put("marketOpen", isMarketOpen());
                return ResponseEntity.ok(stale);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Scan failed for {}: {}", key, e.getMessage());
            if (lastGoodCache.containsKey(key)) {
                Map<String, Object> stale = new LinkedHashMap<>(lastGoodCache.get(key));
                stale.put("stale", true);
                stale.put("staleReason", "Scan error — showing last known opportunities");
                stale.put("lastScannedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("hh:mm:ss a")));
                stale.put("marketOpen", isMarketOpen());
                return ResponseEntity.ok(stale);
            }
            throw e;
        }
    }

    private boolean isMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime open = LocalTime.of(9, 15);
        LocalTime close = LocalTime.of(15, 30);
        return !now.isBefore(open) && !now.isAfter(close);
    }

    private record CachedResult(Map<String, Object> data, long ts) {}
}
