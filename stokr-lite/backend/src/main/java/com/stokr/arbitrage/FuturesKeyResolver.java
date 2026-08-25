package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

public class FuturesKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(FuturesKeyResolver.class);

    // Legacy method for callers that don't pass expiryDate
    public static String resolveFuturesKey(String underlying, ZerodhaSpotPriceFetcher spotPriceFetcher, String spotKey) {
        return resolveFuturesKey(underlying, spotPriceFetcher, spotKey, LocalDate.now());
    }

    public static String resolveFuturesKey(String underlying, ZerodhaSpotPriceFetcher spotPriceFetcher, String spotKey, LocalDate expiryDate) {
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        String futKey = String.format("NFO:%s%02d%sFUT", underlying, yy, mon);

        // We ONLY want the futures contract for the exact month of the options expiry.
        // Falling through to the next month's futures contract causes massive fake
        // arbitrage signals because of the roll premium / cost-of-carry.
        for (int a = 0; a < 3; a++) {
            double[] result = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            if (result != null && result.length > 1 && result[1] > 0) {
                log.info("Resolved futures key for {}: {} (fut={})", underlying, futKey, result[1]);
                return futKey;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        log.warn("Could not resolve active futures for {} (target={})", underlying, futKey);
        return futKey; // return the expected key anyway, the caller will handle missing price
    }

    public static List<String> getCandidateFuturesKeys(String underlying) {
        LocalDate now = LocalDate.now();
        int yy = now.getYear() % 100;
        List<String> keys = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            Month m = now.plusMonths(i).getMonth();
            String mon = m.name().substring(0, 3);
            keys.add(String.format("NFO:%s%02d%sFUT", underlying, yy, mon));
        }
        return keys;
    }
}
