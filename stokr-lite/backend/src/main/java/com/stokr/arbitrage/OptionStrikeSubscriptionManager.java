package com.stokr.arbitrage;

import com.stokr.marketdata.tick.KiteInstrumentTokenCache;
import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Automatically subscribes the active option matrix (ATM +/- 8 strikes for NIFTY & BANKNIFTY)
 * to Kite WebSocket so that LiveOptionMatrix always has sub-millisecond, real-time quotes.
 */
@Slf4j
@Component
public class OptionStrikeSubscriptionManager {

    private static final List<String> UNDERLYINGS = List.of("NIFTY", "BANKNIFTY");
    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK"
    );

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;
    private final KiteInstrumentTokenCache tokenCache;
    private final KiteTickWebSocketClient tickClient;

    public OptionStrikeSubscriptionManager(OptionChainService optionChainService,
                                           ZerodhaSpotPriceFetcher spotPriceFetcher,
                                           KiteInstrumentTokenCache tokenCache,
                                           KiteTickWebSocketClient tickClient) {
        this.optionChainService = optionChainService;
        this.spotPriceFetcher = spotPriceFetcher;
        this.tokenCache = tokenCache;
        this.tickClient = tickClient;
    }

    @PostConstruct
    public void init() {
        // Initial subscribe attempt on startup
        refreshSubscriptions();
    }

    /**
     * Runs every 5 minutes through the trading day to ensure new strikes are
     * subscribed as the underlying spot price drifts.
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshSubscriptions() {
        for (String underlying : UNDERLYINGS) {
            try {
                subscribeUnderlyingStrikes(underlying);
            } catch (Exception e) {
                log.debug("Strike subscription refresh failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private void subscribeUnderlyingStrikes(String underlying) {
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
        double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return;

        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        if (expiry == null) {
            expiry = optionChainService.getMonthlyExpiryDate(underlying);
        }
        if (expiry == null) return;

        int step = OptionChainService.getStrikeStep(underlying);
        int atmStrike = optionChainService.getATMStrike(underlying, spot);

        List<String> symbolsToSubscribe = new ArrayList<>();
        // Subscribe ATM +/- 8 strikes (17 strikes x 2 (CE+PE) = 34 option contracts)
        for (int i = -8; i <= 8; i++) {
            int strike = atmStrike + i * step;
            String ce = optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE");
            String pe = optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE");
            if (ce != null) symbolsToSubscribe.add(ce);
            if (pe != null) symbolsToSubscribe.add(pe);
        }

        // Add Future contract
        if (futKey != null) {
            String futSym = futKey.startsWith("NFO:") ? futKey.substring(4) : futKey;
            symbolsToSubscribe.add(futSym);
        }

        Map<String, Integer> tokenMap = tokenCache.getTokens(symbolsToSubscribe);
        if (!tokenMap.isEmpty()) {
            tickClient.subscribeBatch(tokenMap);
            log.info("Subscribed {} option contracts to WebSocket for {} (ATM={})",
                tokenMap.size(), underlying, atmStrike);
        }
    }
}