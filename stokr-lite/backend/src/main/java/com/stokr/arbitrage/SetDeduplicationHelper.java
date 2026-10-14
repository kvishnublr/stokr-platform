package com.stokr.arbitrage;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SetDeduplicationHelper ensures that:
 * 1. The SAME strategy CAN have multiple sets across different strikes (e.g. Iron Condor Set 1 at 54600/55900,
 *    Iron Condor Set 2 at 54800/56100).
 * 2. Only the EXACT SAME SET (same strikes and legs) is blocked from duplicating.
 */
@Slf4j
public class SetDeduplicationHelper {

    private static final Pattern STRIKE_PATTERN = Pattern.compile("\\b(\\d{4,6})\\b");
    private static final Pattern PRICE_STRIP_PATTERN = Pattern.compile("(@\\s*[\\d.]+|₹\\s*[\\d.]+|—\\s*credit\\s*[\\d.]+|—\\s*debit\\s*[\\d.]+|credit\\s*[\\d.]+|debit\\s*[\\d.]+)");

    /**
     * Returns TRUE only if 'pos' and 'opp' represent the EXACT SAME SET of strikes/legs.
     * Returns FALSE if they are different strike sets (even if they share the same strategyType and underlying).
     */
    public static boolean isSameSet(LivePosition pos, Object oppObj) {
        if (pos == null || oppObj == null) return false;

        String oppUnderlying = null;
        String oppStrategy = null;
        String oppAction = null;
        Integer oppStrike = null;
        String oppExpiry = null;
        List<String> oppSymbols = new ArrayList<>();

        if (oppObj instanceof OptionArbOpportunity opp) {
            oppUnderlying = opp.getUnderlying();
            oppStrategy = opp.getStrategyType();
            oppAction = opp.getAction();
            oppStrike = opp.getStrike();
            if (opp.getExpiryDate() != null) oppExpiry = opp.getExpiryDate().toString();
        } else if (oppObj instanceof Map<?, ?> map) {
            oppUnderlying = map.get("underlying") != null ? String.valueOf(map.get("underlying")) : null;
            oppStrategy = map.get("strategyType") != null ? String.valueOf(map.get("strategyType")) : null;
            oppAction = map.get("action") != null ? String.valueOf(map.get("action")) : null;
            if (map.get("strike") instanceof Number n) oppStrike = n.intValue();
            if (map.get("expiry") != null) oppExpiry = String.valueOf(map.get("expiry"));
            else if (map.get("expiryDate") != null) oppExpiry = String.valueOf(map.get("expiryDate"));

            if (map.get("legList") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> legMap && legMap.get("symbol") instanceof String s) {
                        oppSymbols.add(s.trim());
                    }
                }
            }
        }

        // 1. Underlying must match
        if (oppUnderlying == null || pos.getUnderlying() == null ||
                !oppUnderlying.trim().equalsIgnoreCase(pos.getUnderlying().trim())) {
            return false;
        }

        // 2. Strategy type must match
        if (oppStrategy != null && pos.getStrategyType() != null &&
                !oppStrategy.trim().equalsIgnoreCase(pos.getStrategyType().trim())) {
            return false;
        }

        // 3. Expiry date check (if both provided, must match)
        if (oppExpiry != null && !oppExpiry.isBlank() && pos.getExpiryDate() != null) {
            String posExp = pos.getExpiryDate().toString();
            if (!oppExpiry.equals(posExp)) {
                return false; // Different expiry cycle = different set
            }
        }

        // 4. Exact leg symbols match (most precise)
        List<String> posSymbols = extractLegSymbols(pos);
        if (!posSymbols.isEmpty() && !oppSymbols.isEmpty()) {
            Set<String> set1 = new HashSet<>(posSymbols);
            Set<String> set2 = new HashSet<>(oppSymbols);
            return set1.equals(set2);
        }

        // 5. Compare strike sets extracted from action strings
        List<Integer> posStrikes = extractAllStrikes(pos.getAction(), pos.getStrike());
        List<Integer> oppStrikes = extractAllStrikes(oppAction, oppStrike);
        if (!posStrikes.isEmpty() && !oppStrikes.isEmpty()) {
            return posStrikes.equals(oppStrikes);
        }

        // 6. Action string normalized comparison
        if (pos.getAction() != null && oppAction != null) {
            String normPos = normalizeAction(pos.getAction());
            String normOpp = normalizeAction(oppAction);
            if (!normPos.isBlank() && !normOpp.isBlank()) {
                return normPos.equalsIgnoreCase(normOpp);
            }
        }

        // 7. Fallback: single strike equality
        if (pos.getStrike() != null && oppStrike != null) {
            return pos.getStrike().equals(oppStrike);
        }

        return false;
    }

    public static List<String> extractLegSymbols(LivePosition pos) {
        List<String> list = new ArrayList<>();
        if (pos.getLegs() != null) {
            for (Map<String, Object> leg : pos.getLegs()) {
                if (leg.get("symbol") instanceof String s && !s.isBlank()) list.add(s.trim());
            }
        }
        if (pos.getCeSymbol() != null && !pos.getCeSymbol().isBlank()) list.add(pos.getCeSymbol().trim());
        if (pos.getPeSymbol() != null && !pos.getPeSymbol().isBlank()) list.add(pos.getPeSymbol().trim());
        if (pos.getFutSymbol() != null && !pos.getFutSymbol().isBlank()) list.add(pos.getFutSymbol().trim());
        return list;
    }

    public static List<Integer> extractAllStrikes(String action, Integer fallbackStrike) {
        Set<Integer> strikes = new TreeSet<>();
        if (fallbackStrike != null && fallbackStrike > 0) {
            strikes.add(fallbackStrike);
        }
        if (action != null && !action.isBlank()) {
            Matcher m = STRIKE_PATTERN.matcher(action);
            while (m.find()) {
                try {
                    int val = Integer.parseInt(m.group(1));
                    if (val >= 1000 && val <= 150000) { // realistic strike range
                        strikes.add(val);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return new ArrayList<>(strikes);
    }

    public static String normalizeAction(String action) {
        if (action == null) return "";
        // Remove pricing "@ 708.3", "credit 197.7", etc.
        String cleaned = PRICE_STRIP_PATTERN.matcher(action).replaceAll("");
        // Remove extra spaces and punctuation
        return cleaned.replaceAll("[|()\\[\\],]", " ").replaceAll("\\s+", " ").trim();
    }
}
