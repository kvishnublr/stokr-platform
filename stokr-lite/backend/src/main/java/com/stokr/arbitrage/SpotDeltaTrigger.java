package com.stokr.arbitrage;

import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import com.stokr.marketdata.tick.TickData;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-Driven Trigger: Wakes up strategy evaluation the exact millisecond the spot price moves,
 * eliminating the 20-second fixed clock lag during sudden market breakouts.
 */
@Slf4j
@Component
public class SpotDeltaTrigger {

    private static final long DEBOUNCE_MS = 3000L;
    private static final Map<String, Double> DELTA_THRESHOLDS = Map.of(
        "NIFTY", 15.0,
        "BANKNIFTY", 40.0
    );

    private final KiteTickWebSocketClient tickClient;
    private final MultiLegSpreadScheduler scheduler;

    private final Map<String, Double> lastEvaluatedSpot = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerMs = new ConcurrentHashMap<>();

    public SpotDeltaTrigger(KiteTickWebSocketClient tickClient,
                            MultiLegSpreadScheduler scheduler) {
        this.tickClient = tickClient;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void init() {
        tickClient.addTickListener(this::onTick);
        log.info("SpotDeltaTrigger initialized for real-time event-driven execution");
    }

    public void onTick(TickData tick) {
        if (tick == null || tick.getSymbol() == null || tick.getLtp() == null) return;
        String sym = tick.getSymbol();

        String underlying = null;
        if (sym.contains("NIFTY 50") || "NIFTY".equalsIgnoreCase(sym)) {
            underlying = "NIFTY";
        } else if (sym.contains("NIFTY BANK") || "BANKNIFTY".equalsIgnoreCase(sym)) {
            underlying = "BANKNIFTY";
        }
        if (underlying == null) return;

        double currentSpot = tick.getLtp().doubleValue();
        if (currentSpot <= 0) return;

        Double lastSpot = lastEvaluatedSpot.get(underlying);
        if (lastSpot == null) {
            lastEvaluatedSpot.put(underlying, currentSpot);
            return;
        }

        double deltaThreshold = DELTA_THRESHOLDS.getOrDefault(underlying, 20.0);
        double displacement = Math.abs(currentSpot - lastSpot);

        if (displacement >= deltaThreshold) {
            long now = System.currentTimeMillis();
            Long lastTime = lastTriggerMs.get(underlying);
            if (lastTime != null && (now - lastTime) < DEBOUNCE_MS) {
                return;
            }
            lastTriggerMs.put(underlying, now);
            lastEvaluatedSpot.put(underlying, currentSpot);

            log.info("SPOT_DELTA_TRIGGER: {} moved {:.1f} pts (threshold={:.1f}). Triggering instant real-time scan!",
                underlying, displacement, deltaThreshold);
            scheduler.triggerImmediateScan(underlying);
        }
    }
}