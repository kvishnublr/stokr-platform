package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Generates a Java strategy template file when an admin creates a new strategy.
 * The generated file is written to stokr-strategy/src/main/java/com/stokr/strategy/generated/
 * so a developer can open it directly and implement the logic.
 * NO dynamic compilation — the class must be compiled on next build.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyTemplateGeneratorService {

    private final StrategyDefinitionRepository definitionRepository;

    /**
     * Base source root for the stokr-strategy module.
     * Defaults to src/main/java relative to CWD — override in application.properties
     * with stokr.catalog.template-source-root for non-standard layouts.
     */
    @Value("${stokr.catalog.template-source-root:stokr-strategy/src/main/java}")
    private String templateSourceRoot;

    private static final String GENERATED_PACKAGE = "com.stokr.strategy.generated";
    private static final String GENERATED_PACKAGE_PATH = "com/stokr/strategy/generated";

    /**
     * Generates a Java strategy template for the given strategy definition.
     * Updates template_class_name, generated_class_path, and template_generated on success.
     */
    @Transactional
    public GenerationResult generate(UUID strategyDefinitionId) {
        StrategyDefinition def = definitionRepository.findByIdAndDeletedFalse(strategyDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("Strategy definition not found: " + strategyDefinitionId));

        String className = resolveClassName(def);
        String fqcn = GENERATED_PACKAGE + "." + className;
        Path targetDir = Paths.get(templateSourceRoot, GENERATED_PACKAGE_PATH);
        Path targetFile = targetDir.resolve(className + ".java");

        if (Files.exists(targetFile)) {
            log.info("template.generator.already_exists class={}", fqcn);
            def.setTemplateClassName(className);
            def.setGeneratedClassPath(fqcn);
            def.setTemplateGenerated(true);
            definitionRepository.save(def);
            return new GenerationResult(className, fqcn, targetFile.toString(), false);
        }

        try {
            Files.createDirectories(targetDir);
            String source = buildTemplate(className, def);
            Files.writeString(targetFile, source, StandardCharsets.UTF_8);

            def.setTemplateClassName(className);
            def.setGeneratedClassPath(fqcn);
            def.setTemplateGenerated(true);
            definitionRepository.save(def);

            log.info("template.generator.written class={} path={}", fqcn, targetFile);
            return new GenerationResult(className, fqcn, targetFile.toString(), true);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write strategy template: " + targetFile, ex);
        }
    }

    private String resolveClassName(StrategyDefinition def) {
        if (def.getTemplateClassName() != null && !def.getTemplateClassName().isBlank()) {
            return def.getTemplateClassName();
        }
        // Derive from strategy key: MEAN_REVERSION_V3 -> MeanReversionV3SignalGenerator
        String key = def.getStrategyKey();
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("[_\\-]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase());
            }
        }
        sb.append("SignalGenerator");
        return sb.toString();
    }

    private String buildTemplate(String className, StrategyDefinition def) {
        String assetClass = nvl(def.getAssetClass(), "EQUITY");
        String segment    = nvl(def.getSegment(), "NSE");
        String timeframe  = nvl(def.getDefaultTimeframe(), "1m");
        String exchange   = nvl(def.getDefaultExchange(), "NSE");
        String stratKey   = def.getStrategyKey();
        String today      = LocalDate.now().toString();

        return """
                package com.stokr.strategy.generated;

                import com.stokr.strategy.catalog.GeneratedStrategy;
                import com.stokr.strategy.context.StrategyContext;
                import com.stokr.strategy.engine.TradingStrategy;
                import com.stokr.strategy.signals.SignalType;
                import com.stokr.strategy.signals.StrategySignal;
                import lombok.RequiredArgsConstructor;
                import lombok.extern.slf4j.Slf4j;
                import org.springframework.stereotype.Component;

                /**
                 * Auto-generated strategy template for: %s
                 * Generated on: %s
                 *
                 * Asset class : %s
                 * Segment     : %s
                 * Exchange    : %s
                 * Timeframe   : %s
                 *
                 * TODO: Implement your strategy logic in the evaluate() method.
                 *       This class is compiled normally — no dynamic loading occurs.
                 *       Once you implement the logic, rebuild the project.
                 *
                 * @see com.stokr.strategy.generated.BaseGeneratedStrategy for helper utilities.
                 */
                @Component
                @RequiredArgsConstructor
                @Slf4j
                @GeneratedStrategy(
                    strategyKey  = "%s",
                    assetClass   = "%s",
                    segment      = "%s",
                    exchange     = "%s",
                    timeframe    = "%s"
                )
                public class %s extends BaseGeneratedStrategy implements TradingStrategy {

                    @Override
                    public String key() {
                        return "%s";
                    }

                    @Override
                    public StrategySignal evaluate(StrategyContext context) {
                        String symbol = context.symbol();
                        log.debug("strategy.evaluate strategy=%s symbol={}", symbol);

                        // TODO: Step 1 — Load candle / OHLCV data
                        // Example: List<Candle> candles = loadCandles(context, "%s", 20);

                        // TODO: Step 2 — Compute indicators
                        // Example (equity breakout):
                        //   double high20 = highestHigh(candles, 20);
                        //   double rsi    = computeRsi(candles, 14);

                        // TODO: Step 3 — Evaluate signal condition
                        //   if (currentPrice > high20 && rsi > 55) {
                        //       return bullishSignal(context, "Breakout confirmed", high20, rsi);
                        //   }

                        // TODO (Commodity / Futures): Read lot_size from StrategyUniverseSymbol
                        //   For MCX Gold: lotSize = 1 (or 10 for GOLDM)
                        //   For BANKNIFTY futures: lotSize = 15

                        // TODO (Options): Resolve strike and expiry from universe group
                        //   Use tradingSymbol from strategy_universe_symbols, e.g. BANKNIFTY26MAYFUT

                        return new StrategySignal(SignalType.HOLD, symbol, null, null);
                    }

                    // ─────────────────────────────────────────────────────────────
                    // Add private helper methods below
                    // ─────────────────────────────────────────────────────────────

                }
                """.formatted(
                stratKey, today,
                assetClass, segment, exchange, timeframe,
                stratKey, assetClass, segment, exchange, timeframe,
                className,
                stratKey,
                stratKey, stratKey,
                timeframe
        );
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    public record GenerationResult(String className, String fqcn, String filePath, boolean newFile) {}
}
