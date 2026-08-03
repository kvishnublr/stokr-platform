package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class FuturesKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(FuturesKeyResolver.class);

    public static String resolveFuturesKey(String underlying, ZerodhaSpotPriceFetcher spotPriceFetcher, String spotKey) {
        LocalDate now = LocalDate.now();

        for (int i = 0; i < 3; i++) {
            LocalDate monthDate = now.plusMonths(i);
            int yy = monthDate.getYear() % 100;
            String mon = monthDate.getMonth().name().substring(0, 3);
            String futKey = String.format("NFO:%s%02d%sFUT", underlying, yy, mon);

            double[] result = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            if (result != null && result.length > 1 && result[1] > 0) {
                log.info("Resolved futures key for {}: {} (fut={})", underlying, futKey, result[1]);
                return futKey;
            }
        }

        int yy = now.getYear() % 100;
        String fallback = String.format("NFO:%s%02d%sFUT", underlying, yy, now.getMonth().name().substring(0, 3));
        log.warn("Could not resolve active futures for {}, falling back to {}", underlying, fallback);
        return fallback;
    }

    public static List<String> getCandidateFuturesKeys(String underlying) {
        LocalDate now = LocalDate.now();
        List<String> keys = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            LocalDate monthDate = now.plusMonths(i);
            int yy = monthDate.getYear() % 100;
            String mon = monthDate.getMonth().name().substring(0, 3);
            keys.add(String.format("NFO:%s%02d%sFUT", underlying, yy, mon));
        }
        return keys;
    }
}
