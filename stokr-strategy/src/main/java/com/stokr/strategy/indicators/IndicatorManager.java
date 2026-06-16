package com.stokr.strategy.indicators;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IndicatorManager {

    private final Map<String, BigDecimal> values = new ConcurrentHashMap<>();

    public void put(String name, BigDecimal value) {
        values.put(name, value);
    }

    public BigDecimal get(String name) {
        return values.get(name);
    }
}
