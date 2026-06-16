package com.stokr.strategy.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StrategyConfigManager {

    private final Map<String, String> jsonConfigByKey = new ConcurrentHashMap<>();

    public void put(String strategyKey, String json) {
        jsonConfigByKey.put(strategyKey, json);
    }

    public String get(String strategyKey) {
        return jsonConfigByKey.get(strategyKey);
    }
}
