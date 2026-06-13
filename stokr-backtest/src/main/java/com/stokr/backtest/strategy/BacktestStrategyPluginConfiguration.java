package com.stokr.backtest.strategy;

import com.stokr.strategy.runtime.StrategyRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BacktestStrategyPluginConfiguration {

    private static final List<String> CATALOG_BACKTEST_KEYS = List.of(
            "NSE_SPIKE_DETECTION",
            "EARLY_BREAKOUT",
            "VWAP_BOUNCE",
            "GAP_FILL",
            "ADV_CASH",
            "SECTOR_LAGGARD"
    );

    @Bean
    List<BacktestStrategyPlugin> catalogBacktestPlugins(StrategyRegistry strategyRegistry) {
        return CATALOG_BACKTEST_KEYS.stream()
                .<BacktestStrategyPlugin>map(key -> new RegistryTradingStrategyBacktestPlugin(key, strategyRegistry))
                .toList();
    }
}
