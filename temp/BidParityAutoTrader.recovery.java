package com.stokr.arbitrage;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
public class BidParityAutoTrader {

    @Scheduled(fixedDelayString = "5000", initialDelay = 15000)
    public void tickCycle() {
        // Temporarily disabled during server recovery.
    }

    public boolean isRunning() {
        return false;
    }

    public String getStatus() {
        return "DISABLED";
    }

    public Map<String, Object> getAllLiveTicks() {
        return Collections.emptyMap();
    }
}
