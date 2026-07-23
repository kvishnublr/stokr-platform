package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VolSurfaceService {

    private static final Logger log = LoggerFactory.getLogger(VolSurfaceService.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;

    public VolSurfaceService(OptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    public Map<String, Object> getVolSurface(String underlying, double spotPrice, double futuresPrice) {
        Map<String, Object> surface = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            if (spotPrice <= 0) {
                surface.put("rows", rows);
                surface.put("count", 0);
                return surface;
            }

            LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
            if (expiry == null) {
                surface.put("rows", rows);
                surface.put("count", 0);
                return surface;
            }

            int atmStrike = optionChainService.getATMStrike(underlying, spotPrice);
            List<Integer> strikes = optionChainService.generateStrikes(atmStrike, underlying);
            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            double yearsToExpiry = Math.max(1.0, Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays()) / 365.0;

            for (int strike : strikes) {
                String ceKey = optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE");
                String peKey = optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE");
                OptionChainService.OptionQuote ce = quotes.get(ceKey);
                OptionChainService.OptionQuote pe = quotes.get(peKey);
                if (ce == null || pe == null) continue;
                if (ce.lastPrice <= 0 || pe.lastPrice <= 0) continue;

                double ceIv = BlackScholesCalculator.impliedVolatility(
                    ce.lastPrice, spotPrice, strike, yearsToExpiry, RISK_FREE_RATE, true, 0.01, 100);
                double peIv = BlackScholesCalculator.impliedVolatility(
                    pe.lastPrice, spotPrice, strike, yearsToExpiry, RISK_FREE_RATE, false, 0.01, 100);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strike", strike);
                row.put("moneyness", round4((strike - spotPrice) / spotPrice));
                row.put("ceIv", round2(ceIv * 100.0));
                row.put("peIv", round2(peIv * 100.0));
                row.put("avgIv", round2(((ceIv + peIv) / 2.0) * 100.0));
                row.put("cePrice", round2(ce.lastPrice));
                row.put("pePrice", round2(pe.lastPrice));
                row.put("ceBid", round2(ce.bid));
                row.put("ceAsk", round2(ce.ask));
                row.put("peBid", round2(pe.bid));
                row.put("peAsk", round2(pe.ask));
                rows.add(row);
            }

            rows.sort((a, b) -> Integer.compare(
                ((Number) a.get("strike")).intValue(),
                ((Number) b.get("strike")).intValue()));
        } catch (Exception e) {
            log.error("Vol surface generation failed for {}: {}", underlying, e.getMessage());
        }

        surface.put("underlying", underlying);
        surface.put("spotPrice", spotPrice);
        surface.put("futuresPrice", futuresPrice);
        surface.put("rows", rows);
        surface.put("count", rows.size());
        return surface;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
