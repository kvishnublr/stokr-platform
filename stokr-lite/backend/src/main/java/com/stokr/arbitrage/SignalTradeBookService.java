package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultrafast trade-book for Bid Parity + Box Spread:
 * stored signals + ENTERED/EXITED status + live/exit PnL.
 * Short in-memory cache for History tab loads.
 */
@Service
public class SignalTradeBookService {

    private static final Logger log = LoggerFactory.getLogger(SignalTradeBookService.class);
    private static final long CACHE_TTL_MS = 2000;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Default: exit when live PnL ≥ targetEdge − 10 (e.g. 300 → 290). */
    public static final double AUTO_EXIT_NEAR_BUFFER_DEFAULT = 10.0;

    private final OptionArbOpportunityRepository opportunityRepo;
    private final LivePositionRepository livePositionRepo;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public SignalTradeBookService(OptionArbOpportunityRepository opportunityRepo,
                                  LivePositionRepository livePositionRepo,
                                  OptionChainService optionChainService,
                                  ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.opportunityRepo = opportunityRepo;
        this.livePositionRepo = livePositionRepo;
        this.optionChainService = optionChainService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public Map<String, Object> getTradeBook(String strategyType, String underlying, int days, double minEdge) {
        String needle = normalizeNeedle(strategyType);
        String uKey = underlying == null ? "ALL" : underlying.trim().toUpperCase(Locale.ROOT);
        String cacheKey = needle + "|" + uKey + "|" + days + "|" + minEdge;
        Cached hit = cache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            Map<String, Object> out = new LinkedHashMap<>(hit.payload);
            out.put("cached", true);
            out.put("cacheAgeMs", System.currentTimeMillis() - hit.at);
            return out;
        }

        long t0 = System.currentTimeMillis();
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(Math.max(0, days - 1));
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = today.atTime(LocalTime.MAX);

        List<OptionArbOpportunity> opps = opportunityRepo
                .findByStrategyNeedleAndScanTimeBetween(needle, from, to);

        // Positions for this strategy (active + recent exits)
        List<LivePosition> positions = livePositionRepo.findByStrategyNeedle(needle);
        Map<Long, LivePosition> byOppId = new HashMap<>();
        List<LivePosition> unmatched = new ArrayList<>();
        for (LivePosition p : positions) {
            if (p.getOpportunityId() != null) {
                byOppId.putIfAbsent(p.getOpportunityId(), p);
            } else {
                unmatched.add(p);
            }
        }
        // Fingerprint index for positions without opportunityId — one-shot consume
        Map<String, Deque<LivePosition>> byFingerprint = new HashMap<>();
        for (LivePosition p : unmatched) {
            byFingerprint
                    .computeIfAbsent(fingerprint(p.getUnderlying(), p.getStrike(), p.getAction()),
                            k -> new ArrayDeque<>())
                    .add(p);
        }

        // Precompute live PnL for active entries (batch quotes where possible)
        Map<Long, Double> livePnlByPosId = computeLivePnlForActive(positions);

        List<Map<String, Object>> items = new ArrayList<>();
        Set<Long> usedPosIds = new HashSet<>();
        for (OptionArbOpportunity o : opps) {
            if (!"ALL".equals(uKey) && (o.getUnderlying() == null
                    || !uKey.equalsIgnoreCase(o.getUnderlying()))) continue;
            double edge = o.getEdgeAfterCosts() != null ? o.getEdgeAfterCosts().doubleValue() : 0;
            if (edge < minEdge) continue;

            LivePosition pos = null;
            if (o.getId() != null) pos = byOppId.get(o.getId());
            if (pos == null) {
                Deque<LivePosition> q = byFingerprint.get(
                        fingerprint(o.getUnderlying(), o.getStrike(), o.getAction()));
                if (q != null) {
                    while (!q.isEmpty()) {
                        LivePosition cand = q.pollFirst();
                        if (cand.getId() != null && usedPosIds.contains(cand.getId())) continue;
                        pos = cand;
                        break;
                    }
                }
            }
            if (pos != null && pos.getId() != null) {
                if (usedPosIds.contains(pos.getId())) pos = null;
                else usedPosIds.add(pos.getId());
            }

            Map<String, Object> row = o.toMap();
            enrichTradeFields(row, o, pos, livePnlByPosId);
            items.add(row);
            if (items.size() >= 800) break;
        }

        // Orphan ENTERED/EXITED positions not linked to an opportunity row in this window
        for (LivePosition p : positions) {
            if (p.getId() != null && usedPosIds.contains(p.getId())) continue;
            if (!"ALL".equals(uKey) && (p.getUnderlying() == null
                    || !uKey.equalsIgnoreCase(p.getUnderlying()))) continue;
            if (!isActive(p) && p.getExitedAt() != null
                    && p.getExitedAt().isBefore(from)) continue;
            double edge = p.getTargetEdge() != null ? p.getTargetEdge().doubleValue() : 0;
            if (edge < minEdge && !isActive(p)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getOpportunityId());
            row.put("scanTime", p.getEnteredAt() != null ? p.getEnteredAt().toString() : null);
            row.put("underlying", p.getUnderlying());
            row.put("strike", p.getStrike());
            row.put("action", p.getAction());
            row.put("strategyType", p.getStrategyType());
            row.put("edgeAfterCosts", edge);
            row.put("legs", p.getErrorMessage());
            enrichTradeFields(row, null, p, livePnlByPosId);
            items.add(row);
            if (p.getId() != null) usedPosIds.add(p.getId());
            if (items.size() >= 1000) break;
        }

        items.sort((a, b) -> String.valueOf(b.getOrDefault("scanTime", ""))
                .compareTo(String.valueOf(a.getOrDefault("scanTime", ""))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("strategyType", strategyType);
        payload.put("underlying", uKey);
        payload.put("days", days);
        payload.put("minEdge", minEdge);
        payload.put("items", items);
        payload.put("count", items.size());
        payload.put("queryMs", System.currentTimeMillis() - t0);
        payload.put("cached", false);
        payload.put("startDate", start.toString());
        payload.put("endDate", today.toString());

        long entered = items.stream().filter(i -> "ENTERED".equals(i.get("tradeStatus"))).count();
        long exited = items.stream().filter(i -> "EXITED".equals(i.get("tradeStatus"))).count();
        long signals = items.stream().filter(i -> "SIGNAL".equals(i.get("tradeStatus"))).count();
        payload.put("summary", Map.of(
                "signals", signals,
                "entered", entered,
                "exited", exited
        ));

        cache.put(cacheKey, new Cached(System.currentTimeMillis(), payload));
        return new LinkedHashMap<>(payload);
    }

    /** Active (+ optional today's closed) positions with live PnL. */
    public Map<String, Object> getPositionsBook(String strategyNeedle, boolean includeClosedToday) {
        String needle = strategyNeedle == null ? "" : strategyNeedle.trim();
        List<LivePosition> all = needle.isEmpty()
                ? livePositionRepo.findAll()
                : livePositionRepo.findByStrategyNeedle(needle.isEmpty() ? "BID" : needle);

        LocalDate today = LocalDate.now(IST);
        List<LivePosition> filtered = new ArrayList<>();
        for (LivePosition p : all) {
            if (isActive(p)) {
                filtered.add(p);
                continue;
            }
            if (includeClosedToday && p.getExitedAt() != null
                    && p.getExitedAt().toLocalDate().equals(today)) {
                filtered.add(p);
            }
        }
        filtered.sort((a, b) -> {
            LocalDateTime ta = a.getEnteredAt() != null ? a.getEnteredAt() : a.getCreatedAt();
            LocalDateTime tb = b.getEnteredAt() != null ? b.getEnteredAt() : b.getCreatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        Map<Long, Double> livePnl = computeLivePnlForActive(filtered);
        List<Map<String, Object>> items = new ArrayList<>();
        double openPnl = 0;
        double closedPnl = 0;
        int openN = 0;
        int closedN = 0;
        for (LivePosition p : filtered) {
            Map<String, Object> row = p.toMap();
            boolean active = isActive(p);
            String mode = "PAPER";
            if (p.getCeOrderId() != null && p.getCeOrderId().startsWith("PAPER")) mode = "PAPER";
            else if (p.getErrorMessage() != null && p.getErrorMessage().toUpperCase(Locale.ROOT).contains("PAPER")) mode = "PAPER";
            else mode = "LIVE";
            row.put("mode", mode);
            row.put("tradeStatus", active ? "ENTERED" : (p.getStatus() != null ? p.getStatus().toUpperCase(Locale.ROOT) : "EXITED"));
            Double pnl;
            if (active) {
                pnl = livePnl.getOrDefault(p.getId(),
                        p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0);
                openPnl += pnl;
                openN++;
                row.put("currentPnl", Math.round(pnl * 100.0) / 100.0);
                row.put("exitPnl", null);
            } else {
                pnl = p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
                closedPnl += pnl;
                closedN++;
                row.put("currentPnl", null);
                row.put("exitPnl", Math.round(pnl * 100.0) / 100.0);
            }
            row.put("pnl", Math.round(pnl * 100.0) / 100.0);
            row.put("pnlLabel", pnl >= 0 ? "PROFIT" : "LOSS");
            double target = p.getTargetEdge() != null ? p.getTargetEdge().doubleValue() : 0;
            double autoExitAt = autoExitThreshold(target, AUTO_EXIT_NEAR_BUFFER_DEFAULT);
            row.put("autoExitAt", target > 0 ? autoExitAt : null);
            row.put("autoExitNearBuffer", AUTO_EXIT_NEAR_BUFFER_DEFAULT);
            items.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", System.currentTimeMillis());
        out.put("positions", items);
        out.put("count", items.size());
        out.put("openCount", openN);
        out.put("closedCount", closedN);
        out.put("openPnl", Math.round(openPnl * 100.0) / 100.0);
        out.put("closedPnl", Math.round(closedPnl * 100.0) / 100.0);
        out.put("netPnl", Math.round((openPnl + closedPnl) * 100.0) / 100.0);
        out.put("autoExitNearBuffer", AUTO_EXIT_NEAR_BUFFER_DEFAULT);
        return out;
    }

    /**
     * Exit threshold derived from entry edge: targetEdge − buffer.
     * Example: edge 300, buffer 10 → exit when live PnL ≥ 290.
     */
    public static double autoExitThreshold(double targetEdge, double nearBuffer) {
        if (targetEdge <= 0) return 0;
        double buf = Math.max(0, nearBuffer);
        return Math.round(Math.max(0, targetEdge - buf) * 100.0) / 100.0;
    }

    /**
     * Scan active Bid/Box positions; exit when live PnL ≥ targetEdge − nearBuffer.
     * Also refreshes stored currentPnl for open legs.
     */
    public List<LivePosition> autoExitNearTargetEdge(String strategyNeedle, double nearBuffer) {
        String needle = strategyNeedle == null || strategyNeedle.isBlank() ? "BID" : strategyNeedle.trim();
        List<LivePosition> active = livePositionRepo.findByStrategyNeedle(needle).stream()
                .filter(SignalTradeBookService::isActive)
                .toList();
        if (active.isEmpty()) return List.of();

        Map<Long, Double> livePnl = computeLivePnlForActive(active);
        List<LivePosition> exited = new ArrayList<>();
        for (LivePosition p : active) {
            double pnl = livePnl.getOrDefault(p.getId(),
                    p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0);
            // Keep MTM warm for Positions UI
            p.setCurrentPnl(BigDecimal.valueOf(Math.round(pnl * 100.0) / 100.0));
            livePositionRepo.save(p);

            double target = p.getTargetEdge() != null ? p.getTargetEdge().doubleValue() : 0;
            if (target <= 0) continue;
            double thr = autoExitThreshold(target, nearBuffer);
            if (pnl + 1e-9 >= thr) {
                LivePosition closed = exitPosition(p.getId(), pnl,
                        String.format(Locale.ROOT, "AUTO_EXIT_NEAR_EDGE target=%.2f thr=%.2f pnl=%.2f",
                                target, thr, pnl));
                exited.add(closed);
            }
        }
        return exited;
    }

    public void invalidate() {
        cache.clear();
    }

    public LivePosition findPosition(long positionId) {
        return livePositionRepo.findById(positionId).orElse(null);
    }

    public LivePosition exitPosition(long positionId, Double pnlOverride, String note) {
        LivePosition pos = livePositionRepo.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("Position not found: " + positionId));
        if (!isActive(pos)) {
            return pos; // already exited
        }
        double pnl = pnlOverride != null ? pnlOverride
                : (pos.getCurrentPnl() != null ? pos.getCurrentPnl().doubleValue() : 0);
        // Refresh MTM once on exit if no override
        if (pnlOverride == null) {
            Map<Long, Double> live = computeLivePnlForActive(List.of(pos));
            if (live.containsKey(pos.getId())) pnl = live.get(pos.getId());
        }
        pos.setCurrentPnl(BigDecimal.valueOf(Math.round(pnl * 100.0) / 100.0));
        pos.setStatus("EXITED");
        pos.setExitedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            String prev = pos.getErrorMessage() != null ? pos.getErrorMessage() : "";
            pos.setErrorMessage(prev + " | EXIT " + note);
        }
        livePositionRepo.save(pos);

        if (pos.getOpportunityId() != null) {
            opportunityRepo.findById(pos.getOpportunityId()).ifPresent(o -> {
                o.setStatus("CLOSED");
                o.setExitTime(LocalDateTime.now());
                o.setPnlAfterCosts(pos.getCurrentPnl());
                o.setPnlAmount(pos.getCurrentPnl());
                opportunityRepo.save(o);
            });
        }
        invalidate();
        return pos;
    }

    private void enrichTradeFields(Map<String, Object> row, OptionArbOpportunity o, LivePosition pos,
                                   Map<Long, Double> livePnlByPosId) {
        String tradeStatus = "SIGNAL";
        Double currentPnl = null;
        Double exitPnl = null;
        String enteredAt = null;
        String exitedAt = null;
        Long positionId = null;
        String positionStatus = null;

        if (pos != null) {
            positionId = pos.getId();
            positionStatus = pos.getStatus();
            enteredAt = pos.getEnteredAt() != null ? pos.getEnteredAt().toString() : null;
            exitedAt = pos.getExitedAt() != null ? pos.getExitedAt().toString() : null;
            if (isActive(pos)) {
                tradeStatus = "ENTERED";
                currentPnl = livePnlByPosId.getOrDefault(pos.getId(),
                        pos.getCurrentPnl() != null ? pos.getCurrentPnl().doubleValue() : 0);
            } else if ("EXITED".equalsIgnoreCase(pos.getStatus()) || "CLOSED".equalsIgnoreCase(pos.getStatus())) {
                tradeStatus = "EXITED";
                exitPnl = pos.getCurrentPnl() != null ? pos.getCurrentPnl().doubleValue() : null;
            } else if ("FAILED".equalsIgnoreCase(pos.getStatus())) {
                tradeStatus = "FAILED";
            }
        } else if (o != null) {
            String st = o.getStatus() != null ? o.getStatus().toUpperCase(Locale.ROOT) : "RUNNING";
            if ("CLOSED".equals(st) || "EXITED".equals(st)) {
                tradeStatus = "EXITED";
                exitPnl = o.getPnlAfterCosts() != null ? o.getPnlAfterCosts().doubleValue() : null;
                exitedAt = o.getExitTime() != null ? o.getExitTime().toString() : null;
            } else if ("EXPIRED".equals(st)) {
                tradeStatus = "EXPIRED";
            } else {
                tradeStatus = "SIGNAL";
            }
        }

        row.put("tradeStatus", tradeStatus);
        row.put("status", tradeStatus); // UI convenience alias
        row.put("currentPnl", currentPnl);
        row.put("exitPnl", exitPnl);
        row.put("pnlAfterCosts", exitPnl != null ? exitPnl : currentPnl);
        row.put("enteredAt", enteredAt);
        row.put("exitedAt", exitedAt);
        row.put("exitTime", exitedAt);
        row.put("positionId", positionId);
        row.put("positionStatus", positionStatus);
    }

    private Map<Long, Double> computeLivePnlForActive(List<LivePosition> positions) {
        Map<Long, Double> out = new HashMap<>();
        List<LivePosition> active = positions.stream().filter(SignalTradeBookService::isActive).toList();
        if (active.isEmpty()) return out;

        // Group by underlying for fewer quote batches
        for (LivePosition p : active) {
            try {
                double pnl = estimatePositionPnl(p);
                out.put(p.getId(), Math.round(pnl * 100.0) / 100.0);
            } catch (Exception e) {
                log.debug("Live PnL failed for pos {}: {}", p.getId(), e.getMessage());
                out.put(p.getId(), p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0);
            }
        }
        return out;
    }

    private double estimatePositionPnl(LivePosition p) {
        String strategy = p.getStrategyType() != null ? p.getStrategyType().toUpperCase(Locale.ROOT) : "";
        int lotSize = p.getLotSize() != null ? p.getLotSize() : OptionChainService.getLotSize(p.getUnderlying());
        int lots = p.getLots() != null ? Math.max(1, p.getLots()) : 1;

        double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
        double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
        double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;
        // Recover executable marks from PAPER legs note when columns missing
        if ((ceEntry <= 0 || peEntry <= 0 || futEntry <= 0) && p.getErrorMessage() != null) {
            double[] parsed = parseEntryMarksFromLegs(p.getErrorMessage());
            if (ceEntry <= 0 && parsed[0] > 0) ceEntry = parsed[0];
            if (peEntry <= 0 && parsed[1] > 0) peEntry = parsed[1];
            if (futEntry <= 0 && parsed[2] > 0) futEntry = parsed[2];
        }

        if (ceEntry <= 0 && peEntry <= 0) {
            return p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
        }

        LocalDate expiry = null;
        if (p.getOpportunityId() != null) {
            expiry = opportunityRepo.findById(p.getOpportunityId())
                    .map(OptionArbOpportunity::getExpiryDate).orElse(null);
        }
        if (expiry == null || p.getStrike() == null) {
            return p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
        }

        String ceSym = optionChainService.buildNfoSymbol(p.getUnderlying(), expiry, p.getStrike(), "CE");
        String peSym = optionChainService.buildNfoSymbol(p.getUnderlying(), expiry, p.getStrike(), "PE");
        String futSym = p.getFutSymbol();
        if (futSym == null || futSym.isBlank()) {
            futSym = buildMonthlyFutSymbol(p.getUnderlying());
        }

        List<String> instruments = new ArrayList<>();
        instruments.add(ceSym);
        instruments.add(peSym);
        if (futSym != null && !futSym.isBlank()) instruments.add(futSym);
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
        double ceLive = quotes.containsKey(ceSym) ? midOrLast(quotes.get(ceSym)) : 0;
        double peLive = quotes.containsKey(peSym) ? midOrLast(quotes.get(peSym)) : 0;
        double futLive = 0;
        if (futSym != null && quotes.containsKey(futSym)) {
            futLive = midOrLast(quotes.get(futSym));
        }
        if (futLive <= 0) {
            futLive = liveFuturesPrice(p.getUnderlying());
        }
        if (ceLive <= 0 || peLive <= 0) {
            return p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
        }

        String action = p.getAction() != null ? p.getAction().toUpperCase(Locale.ROOT) : "";
        double pts;
        if (strategy.contains("BOX")) {
            pts = ((ceLive - ceEntry) - (peLive - peEntry));
        } else if (action.contains("CONVERSION") || (action.contains("BUY CE") && action.contains("SELL PE"))) {
            // Long synth (BUY CE / SELL PE) + short futures hedge
            pts = (ceLive - ceEntry) + (peEntry - peLive);
            if (futEntry > 0 && futLive > 0) {
                pts += (futEntry - futLive);
            }
        } else if (action.contains("REVERSAL") || (action.contains("SELL CE") && action.contains("BUY PE"))) {
            // Short synth (SELL CE / BUY PE) + long futures hedge
            pts = (ceEntry - ceLive) + (peLive - peEntry);
            if (futEntry > 0 && futLive > 0) {
                pts += (futLive - futEntry);
            }
        } else {
            pts = (ceLive - ceEntry) + (peEntry - peLive);
            if (futEntry > 0 && futLive > 0) {
                pts += (futEntry - futLive);
            }
        }
        return pts * lotSize * lots;
    }

    /** Parse "BUY 57700 CE @ 809.4 | SELL 57700 PE @ 613.5 | SELL BANKNIFTY FUT @ 57921.6" */
    static double[] parseEntryMarksFromLegs(String note) {
        double ce = 0, pe = 0, fut = 0;
        if (note == null) return new double[]{0, 0, 0};
        try {
            java.util.regex.Matcher mCe = java.util.regex.Pattern
                    .compile("CE\\s*@\\s*([0-9]+(?:\\.[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(note);
            if (mCe.find()) ce = Double.parseDouble(mCe.group(1));
            java.util.regex.Matcher mPe = java.util.regex.Pattern
                    .compile("PE\\s*@\\s*([0-9]+(?:\\.[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(note);
            if (mPe.find()) pe = Double.parseDouble(mPe.group(1));
            java.util.regex.Matcher mFut = java.util.regex.Pattern
                    .compile("FUT\\s*@\\s*([0-9]+(?:\\.[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(note);
            if (mFut.find()) fut = Double.parseDouble(mFut.group(1));
        } catch (Exception ignored) {}
        return new double[]{ce, pe, fut};
    }

    private double liveFuturesPrice(String underlying) {
        try {
            String spotKey = "NSE:" + ("NIFTY".equalsIgnoreCase(underlying) ? "NIFTY 50"
                    : "BANKNIFTY".equalsIgnoreCase(underlying) ? "NIFTY BANK"
                    : "FINNIFTY".equalsIgnoreCase(underlying) ? "NIFTY FIN SERVICE"
                    : "MIDCPNIFTY".equalsIgnoreCase(underlying) ? "NIFTY MID SELECT"
                    : underlying);
            String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
            double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            if (sf != null && sf.length > 1 && sf[1] > 0) return sf[1];
        } catch (Exception e) {
            log.debug("Fut live failed for {}: {}", underlying, e.getMessage());
        }
        return 0;
    }

    private String buildMonthlyFutSymbol(String underlying) {
        LocalDate monthly = optionChainService.getMonthlyExpiry(underlying);
        int yy = monthly.getYear() % 100;
        String mon = monthly.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", underlying.replace(" ", ""), yy, mon);
    }

    private static double midOrLast(OptionChainService.OptionQuote q) {
        if (q == null) return 0;
        if (q.bid > 0 && q.ask > 0) return (q.bid + q.ask) / 2.0;
        return q.lastPrice > 0 ? q.lastPrice : 0;
    }

    private static boolean isActive(LivePosition p) {
        if (p == null || p.getStatus() == null) return false;
        String s = p.getStatus().toUpperCase(Locale.ROOT);
        return "OPEN".equals(s) || "ENTERED".equals(s) || "PARTIAL".equals(s) || "EXECUTING".equals(s);
    }

    private static String fingerprint(String underlying, Integer strike, String action) {
        return String.valueOf(underlying).toUpperCase(Locale.ROOT) + "|"
                + strike + "|"
                + String.valueOf(action).toUpperCase(Locale.ROOT);
    }

    private static String normalizeNeedle(String strategyType) {
        if (strategyType == null || strategyType.isBlank()) return "BID";
        String s = strategyType.trim().toUpperCase(Locale.ROOT);
        if (s.contains("BOX")) return "BOX";
        if (s.contains("CALENDAR")) return "CALENDAR";
        if (s.contains("BID") || s.contains("PARITY")) return "BID";
        return s;
    }

    private record Cached(long at, Map<String, Object> payload) {}
}
