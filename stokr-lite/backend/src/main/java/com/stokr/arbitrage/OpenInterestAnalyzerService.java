package com.stokr.arbitrage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenInterestAnalyzerService {

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    public Map<String, Object> analyzeOI(String underlying) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("underlying", underlying);
        result.put("timestamp", System.currentTimeMillis());

        try {
            String spotKey = "NIFTY".equals(underlying) ? "NSE:NIFTY 50" :
                             "BANKNIFTY".equals(underlying) ? "NSE:NIFTY BANK" :
                             "FINNIFTY".equals(underlying) ? "NSE:NIFTY FIN SERVICE" :
                             "MIDCPNIFTY".equals(underlying) ? "NSE:NIFTY MID SELECT" : "NSE:" + underlying;
                             
            String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
            double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spotPrice = (sf != null && sf.length > 0) ? sf[0] : 0;
            if (spotPrice <= 0) {
                result.put("error", "Spot price not available");
                return result;
            }

            int atmStrike = OptionChainService.getATMStrike(underlying, spotPrice);
            LocalDate expiryDate = optionChainService.getWeeklyExpiryDate(underlying);

            List<Integer> strikes = new ArrayList<>();
            int step = OptionChainService.getStrikeStep(underlying);
            for (int i = -15; i <= 15; i++) {
                strikes.add(atmStrike + i * step);
            }

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

            long totalCallOI = 0;
            long totalPutOI = 0;
            
            Map<Integer, Long> callOIMap = new HashMap<>();
            Map<Integer, Long> putOIMap = new HashMap<>();

            for (int strike : strikes) {
                long ceOi = 0;
                for (String sym : optionChainService.buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE")) {
                    OptionChainService.OptionQuote q = quotes.get(sym);
                    if (q != null && q.openInterest > 0) ceOi += q.openInterest;
                }
                long peOi = 0;
                for (String sym : optionChainService.buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE")) {
                    OptionChainService.OptionQuote q = quotes.get(sym);
                    if (q != null && q.openInterest > 0) peOi += q.openInterest;
                }
                
                callOIMap.put(strike, ceOi);
                putOIMap.put(strike, peOi);
                totalCallOI += ceOi;
                totalPutOI += peOi;
            }

            double pcr = totalCallOI > 0 ? (double) totalPutOI / totalCallOI : 0.0;
            
            int maxPainStrike = 0;
            double minTotalValue = Double.MAX_VALUE;
            
            for (int evalStrike : strikes) {
                double totalValue = 0;
                
                for (int strike : strikes) {
                    long callOI = callOIMap.getOrDefault(strike, 0L);
                    long putOI = putOIMap.getOrDefault(strike, 0L);
                    
                    if (evalStrike > strike) {
                        totalValue += (evalStrike - strike) * callOI;
                    }
                    if (evalStrike < strike) {
                        totalValue += (strike - evalStrike) * putOI;
                    }
                }
                
                if (totalValue < minTotalValue) {
                    minTotalValue = totalValue;
                    maxPainStrike = evalStrike;
                }
            }

            result.put("spotPrice", spotPrice);
            result.put("atmStrike", atmStrike);
            result.put("totalCallOI", totalCallOI);
            result.put("totalPutOI", totalPutOI);
            result.put("pcr", pcr);
            result.put("maxPainStrike", maxPainStrike);
            
            String bias = "NEUTRAL";
            if (pcr > 1.2) bias = "BULLISH (Support Built)";
            else if (pcr < 0.8) bias = "BEARISH (Resistance Built)";
            result.put("directionalBias", bias);

        } catch (Exception e) {
            log.error("Error analyzing OI for {}: {}", underlying, e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }
}
