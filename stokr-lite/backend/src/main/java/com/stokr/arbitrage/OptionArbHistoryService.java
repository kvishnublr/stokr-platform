package com.stokr.arbitrage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbHistoryService {

    private final OptionArbOpportunityRepository repository;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    @Value("${zerodha.api-key:$ZERODHA_API_KEY}")
    private String apiKey;

    private static final ObjectMapper mapper = new ObjectMapper();

    public OptionArbOpportunityRepository getRepository() { return repository; }

    private static final java.time.Duration DEDUP_WINDOW = java.time.Duration.ofMinutes(15);

    /**
     * Save detected opportunities from a scan result.
     * Deduplicates: skips if same underlying+strike+action already saved within DEDUP_WINDOW.
     */
    public List<OptionArbOpportunity> saveOpportunities(List<ArbitrageOpportunity> opportunities, String underlying, String strategyType) {
        List<OptionArbOpportunity> saved = new ArrayList<>();
        if (opportunities == null || opportunities.isEmpty()) return saved;

        int skipped = 0;
        LocalDateTime cutoff = LocalDateTime.now().minus(DEDUP_WINDOW);

        for (ArbitrageOpportunity opp : opportunities) {
            try {
                String underlyingCode = opp.underlying != null ? opp.underlying : underlying;

                long existing = repository.countRecentSimilar(underlyingCode, opp.strike, opp.action, strategyType, cutoff);
                if (existing > 0) {
                    skipped++;
                    continue;
                }

                LocalDate expiry = LocalDate.now().plusDays((long) opp.daysToExpiry);

                OptionArbOpportunity entity = OptionArbOpportunity.builder()
                    .scanTime(LocalDateTime.now())
                    .underlying(underlyingCode)
                    .type(opp.type)
                    .strike(opp.strike)
                    .action(opp.action)
                    .legs(opp.legs)
                    .description(opp.description)
                    .spotPrice(BigDecimal.valueOf(opp.spotPrice))
                    .futuresPrice(BigDecimal.valueOf(opp.futuresPrice))
                    .ceEntryPrice(BigDecimal.valueOf(opp.cePrice))
                    .peEntryPrice(BigDecimal.valueOf(opp.pePrice))
                    .ceBid(BigDecimal.valueOf(opp.ceBid))
                    .ceAsk(BigDecimal.valueOf(opp.ceAsk))
                    .peBid(BigDecimal.valueOf(opp.peBid))
                    .peAsk(BigDecimal.valueOf(opp.peAsk))
                    .edgePoints(BigDecimal.valueOf(opp.edgePoints))
                    .edgeAfterCosts(BigDecimal.valueOf(opp.edgeAfterCosts))
                    .confidence(BigDecimal.valueOf(opp.confidence))
                    .daysToExpiry(BigDecimal.valueOf(opp.daysToExpiry))
                    .expiryDate(expiry)
                    .status(determineStatus(expiry))
                    .strategyType(strategyType != null ? strategyType : "NORMAL_PARITY")
                    .createdAt(LocalDateTime.now())
                    .build();

                if (opp.costBreakdown != null && !opp.costBreakdown.isEmpty()) {
                    try {
                        entity.setCostBreakdown(opp.costBreakdown);
                    } catch (Exception e) {
                        log.warn("Failed to serialize costBreakdown: {}", e.getMessage());
                    }
                }
                if (opp.legList != null && !opp.legList.isEmpty()) {
                    try {
                        entity.setLegList(opp.legList);
                    } catch (Exception e) {
                        log.warn("Failed to serialize legList: {}", e.getMessage());
                    }
                }

                repository.save(entity);
                saved.add(entity);
            } catch (Exception e) {
                log.error("Failed to save opportunity: {}", e.getMessage());
            }
        }
        if (skipped > 0) log.info("Dedup: skipped {} duplicate signals (within {})", skipped, DEDUP_WINDOW);
        log.info("Saved {} opportunities for {} (skipped {} duplicates)", saved.size(), underlying, skipped);
        return saved;
    }

    public List<OptionArbOpportunity> saveIronCondorOpportunities(List<Map<String, Object>> opps) {
        List<OptionArbOpportunity> saved = new ArrayList<>();
        if (opps == null || opps.isEmpty()) return saved;
        int skipped = 0;
        LocalDateTime cutoff = LocalDateTime.now().minus(DEDUP_WINDOW);
        for (Map<String, Object> o : opps) {
            try {
                String underlying = (String) o.getOrDefault("underlying", "NIFTY");
                String action = (String) o.getOrDefault("action", "");
                int strike = toInt(o.get("strike"));

                long existing = repository.countRecentSimilar(underlying, strike, action, "IRON_CONDOR", cutoff);
                if (existing > 0) {
                    skipped++;
                    continue;
                }

                String expiryStr = (String) o.getOrDefault("expiry", LocalDate.now().toString());
                LocalDate expiry = LocalDate.parse(expiryStr);
                OptionArbOpportunity entity = OptionArbOpportunity.builder()
                    .scanTime(LocalDateTime.now())
                    .underlying(underlying)
                    .type("IRON_CONDOR")
                    .strike(strike)
                    .action(action)
                    .legs((String) o.getOrDefault("legs", ""))
                    .description(action)
                    .spotPrice(toBD(o.get("spotPrice")))
                    .futuresPrice(toBD(o.get("spotPrice")))
                    .edgeAfterCosts(toBD(o.get("edgeAfterCosts")))
                    .confidence(toBD(o.get("confidence")))
                    .daysToExpiry(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiry)))
                    .expiryDate(expiry)
                    .status(determineStatus(expiry))
                    .strategyType("IRON_CONDOR")
                    .createdAt(LocalDateTime.now())
                    .build();
                Object legListObj = o.get("legList");
                if (legListObj instanceof List<?> ll) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> cast = (List<Map<String, Object>>) ll;
                        entity.setLegList(cast);
                    } catch (Exception e) {
                        log.warn("Failed to serialize Iron Condor legList: {}", e.getMessage());
                    }
                }
                repository.save(entity);
                saved.add(entity);
            } catch (Exception e) {
                log.error("Failed to save Iron Condor opp: {}", e.getMessage());
            }
        }
        if (skipped > 0) log.info("Iron Condor dedup: skipped {} duplicates", skipped);
        log.info("Saved {} Iron Condor opportunities (skipped {})", saved.size(), skipped);
        return saved;
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public String determineStatus(LocalDate expiryDate) {
        if (expiryDate == null) return "EXPIRED";
        LocalDate today = LocalDate.now();
        if (today.isAfter(expiryDate)) return "EXPIRED";
        return "RUNNING";
    }

    /**
     * Refresh all statuses based on current date vs expiry
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 9 * * 1-5", zone = "Asia/Kolkata")
    public void refreshStatuses() {
        java.time.LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        var all = repository.findAll();
        int updated = 0;
        for (OptionArbOpportunity opp : all) {
            String newStatus = determineStatus(opp.getExpiryDate());
            if (!newStatus.equals(opp.getStatus())) {
                opp.setStatus(newStatus);
                repository.save(opp);
                updated++;
            }
        }
        if (updated > 0) log.info("Refreshed {} opportunity statuses", updated);
    }

    /**
     * Get opportunities for strategy filter
     */
    public List<OptionArbOpportunity> getHistoryByStrategy(String strategyType) {
        return repository.findByStrategyTypeOrderByScanTimeDesc(strategyType);
    }

    /**
     * Fetch historical page of opportunities
     */
    public Page<OptionArbOpportunity> getHistory(int page, int size) {
        return repository.findAllByOrderByScanTimeDesc(PageRequest.of(page, size));
    }

    public Page<OptionArbOpportunity> getHistory(int page, int size, String strategyType) {
        if (strategyType != null && !strategyType.isBlank()) {
            return repository.findByStrategyTypeOrderByScanTimeDesc(strategyType, PageRequest.of(page, size));
        }
        return repository.findAllByOrderByScanTimeDesc(PageRequest.of(page, size));
    }

    public Page<OptionArbOpportunity> getHistory(int page, int size, String strategyType, String underlying, String startDate, String endDate) {
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            java.time.LocalDate start = java.time.LocalDate.parse(startDate);
            java.time.LocalDate end = java.time.LocalDate.parse(endDate);
            java.time.LocalDateTime startDt = start.atStartOfDay();
            java.time.LocalDateTime endDt = end.atTime(java.time.LocalTime.MAX);
            if (strategyType != null && !strategyType.isBlank() && underlying != null && !underlying.isBlank()) {
                return repository.findByStrategyTypeAndUnderlyingAndScanTimeBetweenOrderByScanTimeDesc(strategyType, underlying, startDt, endDt, PageRequest.of(page, size));
            } else if (strategyType != null && !strategyType.isBlank()) {
                return repository.findByStrategyTypeAndScanTimeBetweenOrderByScanTimeDesc(strategyType, startDt, endDt, PageRequest.of(page, size));
            } else if (underlying != null && !underlying.isBlank()) {
                return repository.findByUnderlyingAndScanTimeBetweenOrderByScanTimeDesc(underlying, startDt, endDt, PageRequest.of(page, size));
            } else {
                return repository.findByScanTimeBetweenOrderByScanTimeDesc(startDt, endDt, PageRequest.of(page, size));
            }
        }
        if (strategyType != null && !strategyType.isBlank() && underlying != null && !underlying.isBlank()) {
            return repository.findByStrategyTypeAndUnderlyingOrderByScanTimeDesc(strategyType, underlying, PageRequest.of(page, size));
        }
        if (strategyType != null && !strategyType.isBlank()) {
            return repository.findByStrategyTypeOrderByScanTimeDesc(strategyType, PageRequest.of(page, size));
        }
        return repository.findAllByOrderByScanTimeDesc(PageRequest.of(page, size));
    }

    /**
     * Fetch historical page of opportunities before a cutoff date (excludes today)
     */
    public Page<OptionArbOpportunity> getHistoryPast(int page, int size, LocalDateTime before) {
        return repository.findByScanTimeBeforeOrderByScanTimeDesc(before, PageRequest.of(page, size));
    }

    /**
     * Get opportunities for a specific date
     */
    public List<OptionArbOpportunity> getHistoryByDate(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return repository.findByScanTimeBetween(start, end);
    }

    /**
     * Get opportunities for a specific date with pagination
     */
    public Page<OptionArbOpportunity> getHistoryByDatePage(LocalDate date, Pageable pageable) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return repository.findByScanTimeBetweenOrderByScanTimeDesc(start, end, pageable);
    }

    /**
     * Get daily summary for a date
     */
    public Map<String, Object> getDailySummary(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        long total = repository.countByScanTimeBetween(start, end);
        long closed = repository.countByScanTimeBetweenAndStatus(start, end, "CLOSED");
        long pending = repository.countByScanTimeBetweenAndStatus(start, end, "OPEN");
        long expired = repository.countByScanTimeBetweenAndStatus(start, end, "EXPIRED");
        BigDecimal totalPnl = repository.sumPnlByScanTimeBetween(start, end);
        BigDecimal totalEdge = repository.sumEdgeByScanTimeBetween(start, end);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date.toString());
        summary.put("totalOpportunities", total);
        summary.put("closed", closed);
        summary.put("pending", pending);
        summary.put("expired", expired);
        summary.put("totalPnlAfterCosts", totalPnl != null ? totalPnl : BigDecimal.ZERO);
        summary.put("totalEdgeDetected", totalEdge != null ? totalEdge : BigDecimal.ZERO);

        // Win rate
        if (closed > 0) {
            long wins = repository.findByScanTimeBetween(start, end).stream()
                .filter(o -> "CLOSED".equals(o.getStatus()) && o.getPnlAfterCosts() != null && o.getPnlAfterCosts().compareTo(BigDecimal.ZERO) > 0)
                .count();
            summary.put("winRate", BigDecimal.valueOf(wins * 100.0 / closed).setScale(1, RoundingMode.HALF_UP));
            summary.put("wins", wins);
            summary.put("losses", closed - wins);
        } else {
            summary.put("winRate", BigDecimal.ZERO);
            summary.put("wins", 0);
            summary.put("losses", 0);
        }

        return summary;
    }

    /**
     * Get summary â€” if date is null, aggregates ALL opportunities
     */
    public SummaryResult getSummary(LocalDate date) {
        if (date != null) {
            Map<String, Object> summary = getDailySummary(date);
            SummaryResult result = new SummaryResult();
            result.totalOpportunities = ((Number) summary.get("totalOpportunities")).longValue();
            result.totalEdgeDetected = (BigDecimal) summary.get("totalEdgeDetected");
            result.totalPnlAfterCosts = (BigDecimal) summary.get("totalPnlAfterCosts");
            result.winRate = (BigDecimal) summary.get("winRate");
            result.wins = ((Number) summary.get("wins")).longValue();
            result.losses = ((Number) summary.get("losses")).longValue();
            return result;
        }
        SummaryResult result = new SummaryResult();
        result.totalOpportunities = repository.countAll();
        result.totalEdgeDetected = repository.sumEdgeAll();
        BigDecimal totalPnl = repository.sumPnlAll();
        result.totalPnlAfterCosts = totalPnl != null ? totalPnl : BigDecimal.ZERO;
        long withPnl = repository.countWithPnlAll();
        long wins = repository.countWinsAll();
        result.wins = wins;
        result.losses = withPnl - wins;
        result.winRate = withPnl > 0
            ? BigDecimal.valueOf(wins * 100.0 / withPnl).setScale(1, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        return result;
    }

    @lombok.Data
    public static class SummaryResult {
        private long totalOpportunities;
        private BigDecimal totalEdgeDetected;
        private BigDecimal totalPnlAfterCosts;
        private BigDecimal winRate;
        private long wins;
        private long losses;
    }

    /**
     * Get date list for pagination headers
     */
    public List<LocalDate> getAvailableDates(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return repository.findDistinctScanTimesSince(since).stream()
            .map(LocalDateTime::toLocalDate)
            .distinct()
            .sorted(java.util.Comparator.reverseOrder())
            .toList();
    }

    public List<OptionArbOpportunity> getTodayOpportunities(LocalDate today) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        return repository.findByScanTimeBetween(start, end);
    }

    public List<OptionArbOpportunity> getTodayOpportunities(LocalDate today, String underlying) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        return repository.findByScanTimeBetween(start, end).stream()
            .filter(o -> underlying.equals(o.getUnderlying()))
            .toList();
    }

    public Page<OptionArbOpportunity> getAllHistory(PageRequest pageRequest) {
        return repository.findAllByOrderByScanTimeDesc(pageRequest);
    }

    public Page<OptionArbOpportunity> getHistoryExcludingToday(PageRequest pageRequest) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return repository.findByScanTimeBeforeOrderByScanTimeDesc(todayStart, pageRequest);
    }

    /**
      * Scheduled: After market close (15:45 IST), resolve OPEN opportunities
      * by fetching closing option prices via Zerodha quote API
      */
    @Scheduled(cron = "0 45 15 * * 1-5", zone = "Asia/Kolkata")
    public void resolveOpenOpportunities() {
        log.info("Starting post-market resolution of OPEN option arb opportunities");

        LocalDate today = LocalDate.now();
        List<OptionArbOpportunity> openOpps = repository.findByStatusOrderByScanTimeBetween(
            "OPEN", today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        List<OptionArbOpportunity> detectedOpps = repository.findByStatusOrderByScanTimeBetween(
            "DETECTED", today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        openOpps.addAll(detectedOpps);

        if (openOpps.isEmpty()) {
            log.info("No OPEN opportunities to resolve for {}", today);
            return;
        }

        log.info("Found {} OPEN opportunities to resolve", openOpps.size());

        // Collect all instruments to quote
        Set<String> instruments = new HashSet<>();
        Map<Long, OptionArbOpportunity> oppMap = new HashMap<>();
        for (OptionArbOpportunity opp : openOpps) {
            String ceKey = opp.getUnderlying() + "_" + opp.getStrike() + "_CE";
            String peKey = opp.getUnderlying() + "_" + opp.getStrike() + "_PE";
            instruments.add(ceKey);
            instruments.add(peKey);
            oppMap.put(opp.getId(), opp);
        }

        // Fetch closing quotes from Zerodha
        Map<String, Double> closingPrices = fetchClosingPrices(instruments);

        // Resolve each opportunity
        int resolved = 0;
        for (OptionArbOpportunity opp : openOpps) {
            try {
                String ceKey = opp.getUnderlying() + "_" + opp.getStrike() + "_CE";
                String peKey = opp.getUnderlying() + "_" + opp.getStrike() + "_PE";

                Double ceClose = closingPrices.get(ceKey);
                Double peClose = closingPrices.get(peKey);

                if (ceClose == null && peClose == null) {
                    opp.setStatus("EXPIRED");
                    opp.setNotes("Could not fetch closing prices");
                    repository.save(opp);
                    resolved++;
                    continue;
                }

                // Calculate P&L based on action type
                BigDecimal pnl = BigDecimal.ZERO;
                if ("REVERSAL".equals(opp.getAction())) {
                    // SELL CE + BUY PE + BUY FUT
                    // P&L = (CE_entry - CE_close) + (PE_close - PE_entry) + (FUT_close - FUT_entry)
                    BigDecimal cePnl = opp.getCeEntryPrice().subtract(BigDecimal.valueOf(ceClose != null ? ceClose : 0));
                    BigDecimal pePnl = BigDecimal.valueOf(peClose != null ? peClose : 0).subtract(opp.getPeEntryPrice());
                    pnl = cePnl.add(pePnl);
                } else if ("CONVERSION".equals(opp.getAction())) {
                    // BUY CE + SELL PE + SELL FUT
                    // P&L = (CE_close - CE_entry) + (PE_entry - PE_close) + (FUT_entry - FUT_close)
                    BigDecimal cePnl = BigDecimal.valueOf(ceClose != null ? ceClose : 0).subtract(opp.getCeEntryPrice());
                    BigDecimal pePnl = opp.getPeEntryPrice().subtract(BigDecimal.valueOf(peClose != null ? peClose : 0));
                    pnl = cePnl.add(pePnl);
                } else {
                    // For other actions (IV_SPIKE, SKEW), use simple exit
                    if (ceClose != null && opp.getCeEntryPrice() != null) {
                        pnl = BigDecimal.valueOf(ceClose).subtract(opp.getCeEntryPrice());
                    } else if (peClose != null && opp.getPeEntryPrice() != null) {
                        pnl = BigDecimal.valueOf(peClose).subtract(opp.getPeEntryPrice());
                    }
                }

                int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
                BigDecimal pnlAmount = pnl.multiply(BigDecimal.valueOf(lotSize));
                BigDecimal costs = pnlAmount.abs().multiply(BigDecimal.valueOf(0.0005)).add(BigDecimal.valueOf(50));
                BigDecimal pnlAfterCosts = pnlAmount.subtract(costs);

                opp.setCeExitPrice(ceClose != null ? BigDecimal.valueOf(ceClose) : null);
                opp.setPeExitPrice(peClose != null ? BigDecimal.valueOf(peClose) : null);
                opp.setPnlPoints(pnl);
                opp.setPnlAmount(pnlAmount);
                opp.setPnlAfterCosts(pnlAfterCosts);
                opp.setExitTime(LocalDateTime.now());
                opp.setStatus("CLOSED");
                opp.setNotes(String.format("Resolved: CE=%.1f PE=%.1f P&L=%.1f pts (â‚¹%.0f after costs)",
                    ceClose != null ? ceClose : 0, peClose != null ? peClose : 0,
                    pnl.doubleValue(), pnlAfterCosts.doubleValue()));
                repository.save(opp);
                resolved++;

            } catch (Exception e) {
                log.error("Failed to resolve opportunity {}: {}", opp.getId(), e.getMessage());
                opp.setStatus("ERROR");
                opp.setNotes("Resolution error: " + e.getMessage());
                repository.save(opp);
            }
        }

        log.info("Resolved {}/{} opportunities for {}", resolved, openOpps.size(), today);
    }

    private Map<String, Double> fetchClosingPrices(Set<String> instruments) {
        Map<String, Double> prices = new HashMap<>();
        String token = spotFetcher.getAuthToken();
        if (token == null) {
            log.warn("No auth token for closing price fetch");
            return prices;
        }

        // Build NFO instrument symbols from our keys
        List<String> nfoSymbols = new ArrayList<>();
        for (String key : instruments) {
            String[] parts = key.split("_");
            if (parts.length == 3) {
                String underlying = parts[0];
                String strike = parts[1];
                String type = parts[2];
                // Use today's expiry
                LocalDate expiry = LocalDate.now();
                String nfoSymbol = buildNfoSymbol(underlying, expiry, Integer.parseInt(strike), type);
                nfoSymbols.add(nfoSymbol);
            }
        }

        // Fetch in batches of 50
        for (int i = 0; i < nfoSymbols.size(); i += 50) {
            List<String> batch = nfoSymbols.subList(i, Math.min(i + 50, nfoSymbols.size()));
            StringBuilder url = new StringBuilder("https://api.kite.trade/quote?");
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) url.append("&");
                url.append("i=NFO:").append(batch.get(j));
            }

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
                conn.setRequestProperty("Authorization", "token " + apiKey + ":" + token);
                conn.setRequestProperty("X-Kite-Version", "3");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    InputStream is = conn.getInputStream();
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(body);
                    JsonNode data = root.path("data");

                    if (data.isObject()) {
                        var fields = data.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            JsonNode quoteNode = entry.getValue();
                            if (quoteNode.isObject() && quoteNode.has("last_price")) {
                                String instrument = entry.getKey();
                                double price = quoteNode.get("last_price").asDouble();
                                // Reverse-map NFO symbol back to our key
                                // This is approximate â€” better to store NFO symbol in the entity
                                prices.put(instrument, price);
                            }
                        }
                    }
                }
                conn.disconnect();
                Thread.sleep(350);
            } catch (Exception e) {
                log.warn("Failed to fetch closing prices batch {}: {}", i, e.getMessage());
            }
        }

        return prices;
    }

    private String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        if ("NIFTY".equals(cleanUnderlying)) {
            int month = expiryDate.getMonthValue();
            int day = expiryDate.getDayOfMonth();
            return String.format("%s%02d%d%02d%d%s", cleanUnderlying, yy, month, day, strike, type);
        }
        return String.format("%s%02d%s%d%s", cleanUnderlying, yy, mon, strike, type);
    }

    private String buildNfoFutSymbol(String underlying, LocalDate expiryDate) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", cleanUnderlying, yy, mon);
    }
}


