package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyRepository repository;
    private final List<StrategyPlugin> plugins;

    private Map<String, StrategyPlugin> getPluginMap() {
        return plugins.stream()
                .collect(Collectors.toMap(StrategyPlugin::getStrategyType, Function.identity()));
    }

    public List<Strategy> getAllStrategies() {
        return repository.findAll();
    }

    public List<Strategy> getEnabledStrategies() {
        return repository.findByEnabledTrue();
    }

    public Strategy getStrategy(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + id));
    }

    public Strategy getStrategyByType(String type) {
        return repository.findByStrategyType(type)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + type));
    }

    public StrategyPlugin getPlugin(String strategyType) {
        StrategyPlugin plugin = getPluginMap().get(strategyType);
        if (plugin == null) {
            throw new IllegalArgumentException("No plugin registered for strategy type: " + strategyType);
        }
        return plugin;
    }

    @Transactional
    public Strategy createStrategy(Strategy strategy) {
        return repository.save(strategy);
    }

    @Transactional
    public Strategy toggleEnabled(Long id, boolean enabled) {
        Strategy strategy = getStrategy(id);
        strategy.setEnabled(enabled);
        return repository.save(strategy);
    }

    public Signal evaluateSignal(Long strategyId, MarketContext context) {
        Strategy strategy = getStrategy(strategyId);
        if (!strategy.isEnabled()) return null;

        StrategyPlugin plugin = getPlugin(strategy.getStrategyType());
        StrategyParams params = StrategyParams.defaults(); // TODO: load from strategy.paramsSchema
        return plugin.evaluate(context, params);
    }
}
