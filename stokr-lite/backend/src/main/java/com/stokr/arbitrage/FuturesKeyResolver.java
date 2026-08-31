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
        for (int a = 0; a < 3; a++) {
            LocalDate checkDate = expiryDate.plusMonths(a);
            int yy = checkDate.getYear() % 100;
            String mon = checkDate.getMonth().name().substring(0, 3);
            String futKey = String.format("NFO:%s%02d%sFUT", underlying, yy, mon);

            for (int retry = 0; retry < 2; retry++) {
                double[] result = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                if (result != null && result.length > 1 && result[1] > 0) {
                    log.info("Resolved futures key for {}: {} (fut={})", underlying, futKey, result[1]);
                    return futKey;
                }
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }

        log.warn("Could not resolve active futures for {}", underlying);
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        return String.format("NFO:%s%02d%sFUT", underlying, yy, mon); // return the expected key anyway
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
