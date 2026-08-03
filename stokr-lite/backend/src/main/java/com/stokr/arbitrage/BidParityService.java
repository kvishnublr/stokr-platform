package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;

@Service
public class BidParityService {

    private static final Logger log = LoggerFactory.getLogger(BidParityService.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

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

        List<String> targets = "ALL".equalsIgnoreCase(underlying)
                ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
                : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();

        Map<String, String> spotKeys = Map.of(
                "NIFTY", "NSE:NIFTY 50",
                "BANKNIFTY", "NSE:NIFTY BANK",
                "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
                "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);

                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : 0;

                // Prefer futures for parity; use spot only for ATM when available.
                // Never invent futures from spot — that falsely zeros basis and creates junk edges.
                if (fut <= 0) {
                    log.warn("No futures quote for {} (key={}), skipping Bid Parity scan", u, futKey);
                    continue;
                }
                if (spot <= 0) {
                    log.warn("Index spot missing for {} — using futures {} for ATM only", u, fut);
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
                    addOpps(results, optionChainService.scanBidParityChain(u, spot, fut, futExpiry, true),
                            u, "WEEKLY", true);
                }
            } catch (Exception e) {
                log.error("Error scanning Bid Parity for {}: {}", u, e.getMessage(), e);
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0)).doubleValue()));
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
            map.put("guaranteedFill", false);
            map.put("bidEdgeInr", opp.edgeAfterCosts);
            if (opp.costBreakdown != null) {
                if (opp.costBreakdown.containsKey("parityForward")) {
                    map.put("parityForward", opp.costBreakdown.get("parityForward"));
                }
                if (opp.costBreakdown.containsKey("basisResidual")) {
                    map.put("basisResidual", opp.costBreakdown.get("basisResidual"));
                }
            }
            results.add(map);
        }
    }

    /** Parse NFO:NIFTY25AUGFUT → last monthly expiry for that contract month. */
    public static LocalDate resolveFuturesExpiry(String underlying, String futKey) {
        try {
            String key = futKey == null ? "" : futKey.replace("NFO:", "").toUpperCase(Locale.ROOT);
            // e.g. NIFTY25AUGFUT / BANKNIFTY25AUGFUT
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
