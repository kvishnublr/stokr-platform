package com.stokr.marketdata.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LatestPriceCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    private static String key(String symbol) {
        return "md:last:" + symbol.toUpperCase();
    }

    public void setLastPrice(String symbol, BigDecimal price) {
        redis.opsForValue().set(key(symbol), price.toPlainString(), TTL);
    }

    public BigDecimal getLastPrice(String symbol) {
        String v = redis.opsForValue().get(key(symbol));
        return v == null ? null : new BigDecimal(v);
    }
}
