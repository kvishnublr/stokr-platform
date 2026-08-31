package com.stokr.arbitrage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
public class PortfolioGreeksService {

    private final LivePositionRepository positionRepository;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public PortfolioGreeksService(LivePositionRepository positionRepository,
                                  OptionChainService optionChainService,
                                  ZerodhaSpotPriceFetcher spotFetcher) {
        this.positionRepository = positionRepository;
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public Map<String, Object> calculatePortfolioGreeks() {
        List<LivePosition> openPositions = positionRepository.findAllOpen();
        
        // Aggregate per underlying
        Map<String, PortfolioGreeks> aggregate = new HashMap<>();
        double totalRiskCapital = 0.0;

        for (LivePosition pos : openPositions) {
            String u = pos.getUnderlying();
            if (u == null) continue;
            aggregate.putIfAbsent(u, new PortfolioGreeks(u));

            PortfolioGreeks agg = aggregate.get(u);
            
            // Get live market data for this underlying
            String spotKey = "NSE:" + u;
            if (u.equals("NIFTY")) spotKey = "NSE:NIFTY 50";
            if (u.equals("BANKNIFTY")) spotKey = "NSE:NIFTY BANK";
            if (u.equals("FINNIFTY")) spotKey = "NSE:NIFTY FIN SERVICE";
            if (u.equals("MIDCPNIFTY")) spotKey = "NSE:NIFTY MID SELECT";
            String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotFetcher, spotKey);
            double[] sf = spotFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (sf != null && sf.length > 0) ? sf[0] : 0;
            if (spot <= 0) continue; // Skip if no live spot

            // Parse legs
            List<Map<String, Object>> legs = pos.getLegs();
            if (legs != null && !legs.isEmpty()) {
                for (Map<String, Object> leg : legs) {
                    processLeg(leg, pos.getExpiryDate(), spot, agg);
                }
            } else if (pos.getCeSymbol() != null || pos.getPeSymbol() != null) {
                // Fallback for legacy 1.0 positions
                processLegacyPosition(pos, spot, agg);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("portfolio", aggregate);
        response.put("totalPositions", openPositions.size());
        
        return response;
    }

    private void processLeg(Map<String, Object> leg, LocalDate expiry, double spot, PortfolioGreeks agg) {
        String type = (String) leg.getOrDefault("type", ""); // CE, PE, FUT
        String action = (String) leg.getOrDefault("action", "BUY");
        int qty = 0;
        if (leg.containsKey("qty")) {
            qty = ((Number) leg.get("qty")).intValue();
        } else if (leg.containsKey("quantity")) {
            qty = ((Number) leg.get("quantity")).intValue();
        }
        
        if (qty == 0) return;
        
        int sign = action.equalsIgnoreCase("BUY") ? 1 : -1;
        
        if ("FUT".equalsIgnoreCase(type)) {
            // Futures delta = 1.0 * qty
            agg.addDelta(sign * qty);
            return;
        }
        
        // Option leg
        int strike = 0;
        if (leg.containsKey("strike")) {
            strike = ((Number) leg.get("strike")).intValue();
        }
        if (strike == 0) return; // Can't calculate options greeks without strike
        
        double T = calculateTimeInYears(expiry);
        double r = 0.065; // Risk free rate
        
        // We need the market IV. Since we don't have the live option price here easily without 
        // a bulk fetch, we will estimate IV using the ATM IV from IVRankService, or fallback to 0.15
        // A full implementation would fetch live quotes for all legs and back-calculate IV.
        // For portfolio approximation, 0.15 is used as placeholder if we can't get live IV easily.
        double sigma = 0.18; // Defaulting to 18% IV for now

        BlackScholesCalculator.Greeks greeks;
        if ("CE".equalsIgnoreCase(type)) {
            greeks = BlackScholesCalculator.callGreeks(spot, strike, T, r, sigma);
        } else if ("PE".equalsIgnoreCase(type)) {
            greeks = BlackScholesCalculator.callGreeks(spot, strike, T, r, sigma);
            // Convert call greeks to put greeks
            greeks = new BlackScholesCalculator.Greeks(
                greeks.delta - 1, 
                greeks.gamma, 
                greeks.theta, 
                greeks.vega
            );
        } else {
            return;
        }

        agg.addDelta(greeks.delta * qty * sign);
        agg.addGamma(greeks.gamma * qty * sign);
        agg.addTheta(greeks.theta * qty * sign);
        agg.addVega(greeks.vega * qty * sign);
    }

    private void processLegacyPosition(LivePosition pos, double spot, PortfolioGreeks agg) {
        // Highly simplified fallback
        int qty = (pos.getLots() != null ? pos.getLots() : 1) * (pos.getLotSize() != null ? pos.getLotSize() : 50);
        double T = calculateTimeInYears(pos.getExpiryDate());
        double sigma = 0.18;
        
        if ("REVERSAL".equals(pos.getAction())) {
            // SELL CE, BUY PE, BUY FUT
            if (pos.getStrike() != null) {
                BlackScholesCalculator.Greeks callGreeks = BlackScholesCalculator.callGreeks(spot, pos.getStrike(), T, 0.065, sigma);
                agg.addDelta(-callGreeks.delta * qty);
                agg.addGamma(-callGreeks.gamma * qty);
                agg.addTheta(-callGreeks.theta * qty);
                agg.addVega(-callGreeks.vega * qty);
                
                // Put greeks (delta = callDelta - 1)
                agg.addDelta((callGreeks.delta - 1) * qty);
                agg.addGamma(callGreeks.gamma * qty);
                agg.addTheta(callGreeks.theta * qty);
                agg.addVega(callGreeks.vega * qty);
                
                // Fut greeks
                agg.addDelta(1.0 * qty);
            }
        }
    }

    private double calculateTimeInYears(LocalDate expiry) {
        if (expiry == null) return 7.0 / 365.0; // Assume 7 days if null
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
        if (days < 0) days = 0;
        // Add intra-day portion roughly
        return (days + 0.5) / 365.0;
    }

    public static class PortfolioGreeks {
        public String underlying;
        public double netDelta = 0;
        public double netGamma = 0;
        public double netTheta = 0;
        public double netVega = 0;

        public PortfolioGreeks(String underlying) {
            this.underlying = underlying;
        }
        
        public void addDelta(double d) { netDelta += d; }
        public void addGamma(double g) { netGamma += g; }
        public void addTheta(double t) { netTheta += t; }
        public void addVega(double v) { netVega += v; }
    }
}
