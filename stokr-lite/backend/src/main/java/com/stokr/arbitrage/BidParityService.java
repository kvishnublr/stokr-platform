package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.concurrent.*;

@Service
public class BidParityService {

    private static final Logger log = LoggerFactory.getLogger(BidParityService.class);
    private static final long SCAN_CACHE_TTL_MS = 1500;
    /** Once edge ≥ this, keep showing until market close (even if later scans miss it). */
    private static final double STICKY_MIN_EDGE = 300.0;
    private static final ExecutorService SCAN_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bid-parity-scan");
        t.setDaemon(true);
        return t;
    });

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private final ConcurrentHashMap<String, CachedScan> scanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> futKeyCache = new ConcurrentHashMap<>();
    /** Session sticky signals — key = underlying|strike|action|expiry|mode */
    private final ConcurrentHashMap<String, StickySignal> stickySignals = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicBoolean lastScanTimedOut =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public BidParityService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanBidParity(String underlying) {
        return scanBidParity(underlying, "MONTHLY");
    }

    /**
     * @param expiryMode MONTHLY | WEEKLY | BOTH
     */
    public List<Map<String, Object>> scanBidParity(String underlying, String expiryMode) {
        String mode = expiryMode == null ? "MONTHLY" : expiryMode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MONTHLY", "WEEKLY", "BOTH").contains(mode)) mode = "MONTHLY";
        String uKey = underlying == null ? "ALL" : underlying.trim().toUpperCase(Locale.ROOT);
        String cacheKey = uKey + "|" + mode;

        CachedScan hit = scanCache.get(cacheKey);
        if (hit != null && (System.currentTimeMillis() - hit.atMs) < SCAN_CACHE_TTL_MS) {
            return deepCopy(hit.opps);
        }

        List<String> targets = "ALL".equals(uKey)
                ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
                : List.of(uKey);

        Map<String, String> spotKeys = Map.of(
                "NIFTY", "NSE:NIFTY 50",
                "BANKNIFTY", "NSE:NIFTY BANK",
                "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
                "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (String u : targets) {
            final String modeFinal = mode;
            futures.add(CompletableFuture.supplyAsync(
                    () -> scanOne(u, spotKeys.getOrDefault(u, "NSE:NIFTY 50"), modeFinal),
                    SCAN_POOL));
        }

        // ALL+BOTH hits 4×(weekly+monthly) quote batches — 8s was too tight and returned empty on lag
        long waitSec = "ALL".equals(uKey) ? 20L : 12L;
        boolean timedOut = false;
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(waitSec, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            timedOut = true;
            log.warn("Parallel Bid Parity scan timed out after {}s for {}|{} — collecting partials",
                    waitSec, uKey, mode);
        } catch (Exception e) {
            log.warn("Parallel Bid Parity scan incomplete: {}", e.getMessage());
        }
        for (CompletableFuture<List<Map<String, Object>>> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    List<Map<String, Object>> part = f.getNow(List.of());
                    if (part != null) results.addAll(part);
                } catch (Exception ignored) {}
            }
        }

        // Stick signals ≥ ₹300 for the session — never drop when a later scan misses them
        results = mergeSticky(uKey, mode, results);

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0)).doubleValue()));

        // Never poison cache with empty timeout results (UI would flash "no signals" for 1.5s+)
        if (!(timedOut && results.isEmpty())) {
            scanCache.put(cacheKey, new CachedScan(System.currentTimeMillis(), deepCopy(results)));
        }
        lastScanTimedOut.set(timedOut);
        return results;
    }

    public boolean consumeLastScanTimedOut() {
        return lastScanTimedOut.getAndSet(false);
    }

    /** Purge + upsert sticky, then union live scan with sticky rows for this filter. */
    private List<Map<String, Object>> mergeSticky(String uKey, String mode, List<Map<String, Object>> live) {
        purgeExpiredSticky();
        long now = System.currentTimeMillis();
        Set<String> liveKeys = new HashSet<>();

        for (Map<String, Object> opp : live) {
            double edge = ((Number) opp.getOrDefault("edgeAfterCosts", 0)).doubleValue();
            String key = stickyKey(opp);
            liveKeys.add(key);
            if (edge >= STICKY_MIN_EDGE) {
                Map<String, Object> copy = new LinkedHashMap<>(opp);
                StickySignal prev = stickySignals.get(key);
                double peak = edge;
                if (prev != null && prev.payload.get("peakEdgeAfterCosts") instanceof Number n) {
                    peak = Math.max(peak, n.doubleValue());
                }
                copy.put("sticky", true);
                copy.put("live", true);
                copy.put("peakEdgeAfterCosts", peak);
                copy.put("firstSeenAt", prev != null ? prev.firstSeenAt : now);
                copy.put("lastSeenAt", now);
                stickySignals.put(key, new StickySignal(
                        copy,
                        prev != null ? prev.firstSeenAt : now,
                        now,
                        LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                ));
                opp.put("sticky", true);
                opp.put("live", true);
                opp.put("peakEdgeAfterCosts", peak);
                opp.put("firstSeenAt", stickySignals.get(key).firstSeenAt);
                opp.put("lastSeenAt", now);
            } else {
                opp.put("sticky", false);
                opp.put("live", true);
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>(live);
        for (Map.Entry<String, StickySignal> e : stickySignals.entrySet()) {
            if (liveKeys.contains(e.getKey())) continue;
            StickySignal s = e.getValue();
            if (!matchesFilter(s.payload, uKey, mode)) continue;
            Map<String, Object> row = new LinkedHashMap<>(s.payload);
            row.put("sticky", true);
            row.put("live", false); // not in this scan — kept from earlier
            row.put("firstSeenAt", s.firstSeenAt);
            row.put("lastSeenAt", s.lastSeenAt);
            merged.add(row);
        }
        return merged;
    }

    private void purgeExpiredSticky() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        // Clear after market close or next calendar day
        boolean afterClose = now.isAfter(java.time.LocalTime.of(15, 35));
        stickySignals.entrySet().removeIf(e ->
                !today.equals(e.getValue().day) || afterClose);
        if (afterClose) stickySignals.clear();
    }

    private static boolean matchesFilter(Map<String, Object> opp, String uKey, String mode) {
        String u = String.valueOf(opp.getOrDefault("underlying", "")).toUpperCase(Locale.ROOT);
        if (!"ALL".equals(uKey) && !uKey.equals(u)) return false;
        String em = String.valueOf(opp.getOrDefault("expiryMode", "MONTHLY")).toUpperCase(Locale.ROOT);
        if ("BOTH".equals(mode)) return true;
        return mode.equals(em);
    }

    private static String stickyKey(Map<String, Object> opp) {
        return String.valueOf(opp.getOrDefault("underlying", "")).toUpperCase(Locale.ROOT) + "|"
                + opp.getOrDefault("strike", 0) + "|"
                + String.valueOf(opp.getOrDefault("action", "")).toUpperCase(Locale.ROOT) + "|"
                + opp.getOrDefault("expiryDate", "") + "|"
                + String.valueOf(opp.getOrDefault("expiryMode", "")).toUpperCase(Locale.ROOT);
    }

    private List<Map<String, Object>> scanOne(String u, String spotKey, String mode) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String futKey = futKeyCache.computeIfAbsent(u,
                    k -> FuturesKeyResolver.resolveFuturesKey(k, spotPriceFetcher, spotKey));

            double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
            double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : 0;

            if (fut <= 0) {
                // stale cached key — retry resolve once
                futKeyCache.remove(u);
                futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
                futKeyCache.put(u, futKey);
                spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : 0;
            }
            if (fut <= 0) {
                log.warn("No futures quote for {} (key={}), skipping Bid Parity scan", u, futKey);
                return results;
            }
            if (spot <= 0) {
                log.warn("Index spot missing for {} — monthly uses fut {}; weekly uses ATM-implied forward", u, fut);
                spot = fut;
            }

            LocalDate futExpiry = resolveFuturesExpiry(u, futKey);
            log.info("Scanning Bid Parity for {}: spot={}, fut={}, basis={}, futExpiry={}, key={}, mode={}",
                    u, spot, fut, String.format("%.2f", fut - spot), futExpiry, futKey, mode);

            if ("MONTHLY".equals(mode) || "BOTH".equals(mode)) {
                addOpps(results, optionChainService.scanBidParityChain(u, spot, fut, futExpiry, false),
                        u, "MONTHLY", false);
            }
            if ("WEEKLY".equals(mode) || "BOTH".equals(mode)) {
                // Weekly always attempted — OptionChainService falls back to ATM-implied F
                addOpps(results, optionChainService.scanBidParityChain(u, spot, fut, futExpiry, true),
                        u, "WEEKLY", true);
            }
        } catch (Exception e) {
            log.error("Error scanning Bid Parity for {}: {}", u, e.getMessage(), e);
        }
        return results;
    }

    private void addOpps(List<Map<String, Object>> results, List<ArbitrageOpportunity> opps,
                         String underlying, String expiryMode, boolean weekly) {
        if (opps == null || opps.isEmpty()) return;
        String strategyType = weekly ? "BID_PARITY_WEEKLY" : "BID_PARITY";
        historyService.saveOpportunities(opps, underlying, strategyType);
        for (ArbitrageOpportunity opp : opps) {
            Map<String, Object> map = opp.toMap();
            map.put("strategyType", strategyType);
            map.put("expiryMode", expiryMode);
            map.put("basisRisk", weekly);
            map.put("parityModel", "BLACK76_FUTURES");
            map.put("guaranteedFill", false);
            map.put("bidEdgeInr", opp.edgeAfterCosts);
            if (opp.costBreakdown != null) {
                if (opp.costBreakdown.containsKey("parityForward")) {
                    map.put("parityForward", opp.costBreakdown.get("parityForward"));
                }
                if (opp.costBreakdown.containsKey("basisResidual")) {
                    map.put("basisResidual", opp.costBreakdown.get("basisResidual"));
                }
                if (opp.costBreakdown.containsKey("fairSynth")) {
                    map.put("fairSynth", opp.costBreakdown.get("fairSynth"));
                }
                if (opp.costBreakdown.containsKey("df")) {
                    map.put("df", opp.costBreakdown.get("df"));
                }
                for (String qk : List.of("ceBidQty", "ceAskQty", "peBidQty", "peAskQty")) {
                    if (opp.costBreakdown.containsKey(qk)) map.put(qk, opp.costBreakdown.get(qk));
                }
            }
            results.add(map);
        }
    }

    private static List<Map<String, Object>> deepCopy(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) out.add(new LinkedHashMap<>(m));
        return out;
    }

    private record CachedScan(long atMs, List<Map<String, Object>> opps) {}

    private record StickySignal(Map<String, Object> payload, long firstSeenAt, long lastSeenAt, LocalDate day) {}

    /** Parse NFO:NIFTY25AUGFUT → last monthly expiry for that contract month. */
    public static LocalDate resolveFuturesExpiry(String underlying, String futKey) {
        try {
            String key = futKey == null ? "" : futKey.replace("NFO:", "").toUpperCase(Locale.ROOT);
            int futIdx = key.lastIndexOf("FUT");
            if (futIdx > 5) {
                String mon = key.substring(futIdx - 3, futIdx);
                String yyStr = key.substring(futIdx - 5, futIdx - 3);
                int yy = Integer.parseInt(yyStr);
                int year = 2000 + yy;
                Month month = Month.valueOf(mon);
                return lastExpiryOf(underlying, year, month.getValue());
            }
        } catch (Exception ignored) {
        }
        return lastExpiryOf(underlying, LocalDate.now().getYear(), LocalDate.now().getMonthValue());
    }

    private static LocalDate lastExpiryOf(String underlying, int year, int month) {
        java.time.DayOfWeek target = switch (underlying.toUpperCase(Locale.ROOT)) {
            case "BANKNIFTY" -> java.time.DayOfWeek.WEDNESDAY;
            case "FINNIFTY" -> java.time.DayOfWeek.TUESDAY;
            case "MIDCPNIFTY" -> java.time.DayOfWeek.MONDAY;
            default -> java.time.DayOfWeek.TUESDAY;
        };
        LocalDate d = LocalDate.of(year, month, 1).withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        while (d.getDayOfWeek() != target) d = d.minusDays(1);
        return d;
    }
}
