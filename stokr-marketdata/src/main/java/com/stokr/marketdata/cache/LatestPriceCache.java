package com.stokr.marketdata.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class LatestPriceCache {

    private final StringRedisTemplate redis;

    private static String key(String symbol) {
        return "md:last:" + symbol.toUpperCase();
    }

    public void setLastPrice(String symbol, BigDecimal price) {
        redis.opsForValue().set(key(symbol), price.toPlainString());
    }

    public BigDecimal getLastPrice(String symbol) {
        String v = redis.opsForValue().get(key(symbol));
        return v == null ? null : new BigDecimal(v);
    }
}
