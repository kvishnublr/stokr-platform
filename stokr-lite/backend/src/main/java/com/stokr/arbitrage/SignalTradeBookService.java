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

    private final OptionArbOpportunityRepository opportunityRepo;
    private final LivePositionRepository livePositionRepo;
    private final OptionChainService optionChainService;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public SignalTradeBookService(OptionArbOpportunityRepository opportunityRepo,
                                  LivePositionRepository livePositionRepo,
                                  OptionChainService optionChainService) {
        this.opportunityRepo = opportunityRepo;
        this.livePositionRepo = livePositionRepo;
        this.optionChainService = optionChainService;
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

    public void invalidate() {
        cache.clear();
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

        // Paper without entry marks: show 0 until we have quotes + entry
        double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
        double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
        double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;

        if (ceEntry <= 0 && peEntry <= 0) {
            // No marks yet — PnL unknown; keep stored currentPnl (usually 0)
            return p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
        }

        // Need expiry from linked opportunity if present
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
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSym, peSym));
        double ceLive = quotes.containsKey(ceSym) ? midOrLast(quotes.get(ceSym)) : 0;
        double peLive = quotes.containsKey(peSym) ? midOrLast(quotes.get(peSym)) : 0;
        if (ceLive <= 0 || peLive <= 0) {
            return p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0;
        }

        String action = p.getAction() != null ? p.getAction().toUpperCase(Locale.ROOT) : "";
        double pts;
        if (strategy.contains("BOX")) {
            // Approximate: use option legs only vs entry (fut not tracked for paper box)
            // LONG BOX paid debit at entry; PnL ≈ change in box value
            pts = ((ceLive - ceEntry) - (peLive - peEntry)); // crude single-strike proxy if only ATM stored
            // Prefer target-edge decay not available — keep option MTM delta
        } else if (action.contains("CONVERSION") || (action.contains("BUY CE") && action.contains("SELL PE"))) {
            pts = (ceLive - ceEntry) + (peEntry - peLive);
            if (futEntry > 0) {
                // Conversion shorts fut — need fut live; skip if missing
            }
        } else if (action.contains("REVERSAL") || (action.contains("SELL CE") && action.contains("BUY PE"))) {
            pts = (ceEntry - ceLive) + (peLive - peEntry);
        } else {
            pts = (ceLive - ceEntry) + (peEntry - peLive);
        }
        return pts * lotSize * lots;
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
