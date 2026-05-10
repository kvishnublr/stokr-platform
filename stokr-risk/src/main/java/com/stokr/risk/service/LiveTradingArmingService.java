package com.stokr.risk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator-controlled arming for LIVE execution (in addition to {@code stokr.execution.live-trading-enabled}).
 */
@Service
@RequiredArgsConstructor
public class LiveTradingArmingService {

    private static final String KEY = "stokr:live_trading_armed";

    private final StringRedisTemplate redis;

    public boolean isArmed() {
        String v = redis.opsForValue().get(KEY);
        return v != null && ("1".equals(v) || Boolean.parseBoolean(v));
    }

    public void setArmed(boolean armed) {
        redis.opsForValue().set(KEY, armed ? "1" : "0");
    }
}
