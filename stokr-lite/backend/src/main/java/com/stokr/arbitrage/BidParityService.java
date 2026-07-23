package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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

        String yy = String.format("%02d", LocalDate.now().getYear() % 100);
        String mon = LocalDate.now().getMonth().name().substring(0, 3);

        Map<String, String> futKeys = Map.of(
            "NIFTY", "NFO:NIFTY" + yy + mon + "FUT",
            "BANKNIFTY", "NFO:BANKNIFTY" + yy + mon + "FUT",
            "MIDCPNIFTY", "NFO:MIDCPNIFTY" + yy + mon + "FUT",
            "FINNIFTY", "NFO:FINNIFTY" + yy + mon + "FUT"
        );

        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = futKeys.getOrDefault(u, "NFO:" + u + yy + mon + "FUT");

                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;

                if (spot <= 0 && fut > 0) spot = fut;
                if (fut <= 0 && spot > 0) fut = spot;

                log.info("Scanning Bid Parity for {}: spot={}, fut={}", u, spot, fut);

                if (spot <= 0 || fut <= 0) continue;

                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut);
                if (opps != null && !opps.isEmpty()) {
                    historyService.saveOpportunities(opps, u, "BID_PARITY");

                    for (ArbitrageOpportunity opp : opps) {
                        Map<String, Object> map = opp.toMap();
                        map.put("strategyType", "BID_PARITY");
                        map.put("guaranteedFill", true);
                        map.put("bidEdgeInr", opp.edgeAfterCosts);
                        results.add(map);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Bid Parity for {}: {}", u, e.getMessage(), e);
            }
        }

        return results;
    }
}
