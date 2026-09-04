package com.stokr.arbitrage;

public class ZerodhaRateLimiter {
    private static long lastApiCallTime = 0;
    
    public static synchronized void acquire() {
        long now = System.currentTimeMillis();
        long gap = now - lastApiCallTime;
        if (gap < 350) {
            try {
                Thread.sleep(350 - gap);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastApiCallTime = System.currentTimeMillis();
    }
}
