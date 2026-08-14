package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

public class FuturesKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(FuturesKeyResolver.class);

    public static String resolveFuturesKey(String underlying, ZerodhaSpotPriceFetcher spotPriceFetcher, String spotKey) {
        LocalDate now = LocalDate.now();
        int yy = now.getYear() % 100;

        List<Month> candidates = List.of(
            now.getMonth(),
            now.plusMonths(1).getMonth(),
            now.plusMonths(2).getMonth()
        );

        for (int ci = 0; ci < candidates.size(); ci++) {
            Month m = candidates.get(ci);
            String mon = m.name().substring(0, 3);
            String futKey = String.format("NFO:%s%02d%sFUT", underlying, yy, mon);

            // Retry the near-month (current) contract once before falling through to the
            // next month -- a single transient quote-fetch blip on the correct contract
            // must not silently substitute a different expiry month's futures price
            // (which trades at a genuinely different level and would look like a fake
            // parity mispricing against same-month options).
            int attempts = (ci == 0) ? 2 : 1;
            for (int a = 0; a < attempts; a++) {
                double[] result = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                if (result != null && result.length > 1 && result[1] > 0) {
                    log.info("Resolved futures key for {}: {} (fut={})", underlying, futKey, result[1]);
                    return futKey;
                }
            }
        }

        String fallback = String.format("NFO:%s%02d%sFUT", underlying, yy, now.getMonth().name().substring(0, 3));
        log.warn("Could not resolve active futures for {}, falling back to {}", underlying, fallback);
        return fallback;
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
