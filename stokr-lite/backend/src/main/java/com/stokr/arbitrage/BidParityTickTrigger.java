package com.stokr.arbitrage;

import com.stokr.marketdata.tick.KiteInstrumentTokenCache;
import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import com.stokr.marketdata.tick.TickData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Subscribes to live Kite ticks for the CE/PE/FUT instruments Bid Parity scans, and triggers
 * an immediate re-scan of the affected underlying (debounced) when a relevant tick arrives,
 * instead of waiting for the next 15s timer tick. Pricing itself still comes from
 * OptionChainService's REST quote fetch inside BidParityService — ticks here are only a
 * "something moved, re-scan now" signal, not a new pricing source, so the proven quote/edge
 * math is unchanged. BidParityService.scheduledScan (every 15s) remains as a fallback in case
 * the tick feed is down or a symbol hasn't been subscribed yet.
 */
@Component
public class BidParityTickTrigger {

    private static final Logger log = LoggerFactory.getLogger(BidParityTickTrigger.class);
    private static final long DEBOUNCE_MS = 2000L;
    private static final List<String> UNDERLYINGS = List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY");

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    private final BidParityService bidParityService;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;
    private final KiteInstrumentTokenCache tokenCache;
    private final KiteTickWebSocketClient tickClient;

    private final Map<String, String> symbolToUnderlying = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerMs = new ConcurrentHashMap<>();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bid-parity-tick-scan");
        t.setDaemon(true);
        return t;
    });

    public BidParityTickTrigger(BidParityService bidParityService,
                                 OptionChainService optionChainService,
                                 ZerodhaSpotPriceFetcher spotPriceFetcher,
                                 KiteInstrumentTokenCache tokenCache,
                                 KiteTickWebSocketClient tickClient) {
        this.bidParityService = bidParityService;
        this.optionChainService = optionChainService;
        this.spotPriceFetcher = spotPriceFetcher;
        this.tokenCache = tokenCache;
        this.tickClient = tickClient;
    }

    @PostConstruct
    public void init() {
        tickClient.addTickListener(this::onTick);
    }

    /**
     * Rebuild the watched CE/PE/FUT symbol set from the current ATM range. Runs at market open
     * and every 10 minutes through the day since the ATM strike drifts with spot; subscriptions
     * only accumulate (no unsubscribe), which stays well within Kite's per-connection token limit
     * for just 4 underlyings x ~23 instruments.
     */
    @Scheduled(cron = "0 0/10 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshSubscriptions() {
        for (String underlying : UNDERLYINGS) {
            try {
                subscribeUnderlying(underlying);
            } catch (Exception e) {
                log.debug("Bid parity tick subscription failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private void subscribeUnderlying(String underlying) {
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
        double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
        if (spot <= 0 && fut > 0) spot = fut;
        if (spot <= 0) return;

        LocalDate expiry = optionChainService.getMonthlyExpiryDate(underlying);
        if (expiry == null) return;

        int atmStrike = optionChainService.getATMStrike(underlying, spot);
        int step = OptionChainService.getStrikeStep(underlying);

        Map<String, Integer> toSubscribe = new LinkedHashMap<>();
        for (int i = -2; i <= 2; i++) {
            int strike = atmStrike + i * step;
            addIfKnown(toSubscribe, optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE"), underlying);
            addIfKnown(toSubscribe, optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE"), underlying);
        }
        String futSymbol = futKey.startsWith("NFO:") ? futKey.substring(4) : futKey;
        addIfKnown(toSubscribe, futSymbol, underlying);

        if (!toSubscribe.isEmpty()) {
            tickClient.subscribeBatch(toSubscribe);
        }
    }

    private void addIfKnown(Map<String, Integer> out, String symbol, String underlying) {
        if (symbol == null) return;
        if (symbolToUnderlying.containsKey(symbol)) return;
        Integer token = tokenCache.getToken(symbol);
        if (token != null) {
            out.put(symbol, token);
            symbolToUnderlying.put(symbol, underlying);
        }
    }

    private void onTick(TickData tick) {
        String underlying = symbolToUnderlying.get(tick.getSymbol());
        if (underlying == null) return;

        long now = System.currentTimeMillis();
        Long last = lastTriggerMs.get(underlying);
        if (last != null && now - last < DEBOUNCE_MS) return;
        lastTriggerMs.put(underlying, now);

        scanExecutor.submit(() -> {
            try {
                LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
                if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) return;
                bidParityService.scanBidParitySingle(underlying);
            } catch (Exception e) {
                log.debug("Tick-triggered bid parity scan failed for {}: {}", underlying, e.getMessage());
            }
        });
    }
}
