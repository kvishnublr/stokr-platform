package com.stokr.trading.service.exit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.stokr.trading.model.Position;
import com.stokr.trading.repository.PositionRepository;
import com.stokr.trading.repository.ExitSignalRepository;
import com.stokr.trading.model.ExitSignal;
import com.stokr.broker.zerodha.ZerodhaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hybrid Exit Engine Service
 * Combines Strategy Exits + Indicator-Based Exits + AI Optimization
 *
 * Layer 1: Strategy-based exit signals
 * Layer 2: Technical indicator analysis (RSI, MACD, Bollinger Bands, ATR, Volume)
 * Layer 3: Dynamic target calculation with confidence scoring
 */
@Service
public class HybridExitService {

    private static final Logger logger = LoggerFactory.getLogger(HybridExitService.class);

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private ExitSignalRepository exitSignalRepository;

    @Autowired
    private TechnicalIndicatorService indicatorService;

    @Autowired
    private ZerodhaAPI zerodhaAPI;

    /**
     * Main hybrid exit processing - runs every 10 seconds in production
     * Checks all OPEN positions and generates exit decisions
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 5000)  // Run every 10 seconds
    public void processHybridExits() {
        try {
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("HYBRID EXIT ENGINE - Processing Cycle Started");
            logger.info("═══════════════════════════════════════════════════════════════");

            // Get all OPEN positions
            List<Position> openPositions = positionRepository.findByStatus("OPEN");

            if (openPositions.isEmpty()) {
                logger.info("No open positions to process");
                return;
            }

            logger.info("Found {} open positions to analyze", openPositions.size());

            // Process each position
            for (Position position : openPositions) {
                try {
                    processPositionHybridExit(position);
                } catch (Exception e) {
                    logger.error("Error processing position {}: {}", position.getSymbol(), e.getMessage());
                }
            }

            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("HYBRID EXIT ENGINE - Processing Cycle Completed");
            logger.info("═══════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            logger.error("Critical error in hybrid exit engine: {}", e.getMessage(), e);
        }
    }

    /**
     * Process a single position through all three layers
     */
    private void processPositionHybridExit(Position position) {
        String symbol = position.getSymbol();
        BigDecimal currentPrice = zerodhaAPI.getLatestPrice(symbol);

        if (currentPrice == null) {
            logger.warn("Could not fetch current price for {}", symbol);
            return;
        }

        logger.info("─────────────────────────────────────────────────────────────────");
        logger.info("Processing Position: {} | Qty: {} | Entry: {} | Current: {}",
            symbol, position.getQuantity(), position.getEntryPrice(), currentPrice);
        logger.info("─────────────────────────────────────────────────────────────────");

        // LAYER 1: Strategy Exit Signal
        String strategyExitSignal = getStrategyExitSignal(position);
        double strategyConfidence = strategyExitSignal != null ? 0.8 : 0.0;

        logger.info("LAYER 1 - Strategy Exit: {} (Confidence: {})",
            strategyExitSignal != null ? strategyExitSignal : "NONE", strategyConfidence);

        // LAYER 2: Indicator-Based Signals
        IndicatorSignalResult indicatorSignals = generateIndicatorSignals(
            position, currentPrice
        );

        logger.info("LAYER 2 - Indicator Signals: {} (Confidence: {}, Recommendation: {})",
            indicatorSignals.getSignalCount(),
            String.format("%.2f", indicatorSignals.getConfidence()),
            indicatorSignals.getRecommendation());

        // Log individual signals
        for (String signal : indicatorSignals.getSignals()) {
            logger.info("  ├─ {}", signal);
        }

        // LAYER 3: Dynamic Target Calculation
        DynamicTargetResult dynamicTarget = calculateDynamicTarget(
            position, currentPrice, indicatorSignals
        );

        logger.info("LAYER 3 - Dynamic Target Calculation:");
        logger.info("  ├─ Original Target: {}", position.getTargetPrice());
        logger.info("  ├─ Dynamic Target: {}", String.format("%.2f", dynamicTarget.getDynamicTarget()));
        logger.info("  ├─ Dynamic Stop: {}", String.format("%.2f", dynamicTarget.getDynamicStop()));
        logger.info("  ├─ Profit Potential: {}%", String.format("%.2f", dynamicTarget.getProfitPotential()));
        logger.info("  └─ Risk Level: {}%", String.format("%.2f", dynamicTarget.getRiskLevel()));

        // Combined Decision
        HybridExitDecision decision = makeHybridExitDecision(
            position, currentPrice, strategyExitSignal, strategyConfidence, indicatorSignals, dynamicTarget
        );

        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("HYBRID EXIT DECISION: {}", decision.getFinalAction());
        logger.info("  ├─ Combined Confidence: {}%", String.format("%.2f", decision.getCombinedConfidence() * 100));
        logger.info("  ├─ Exit Target: {}", String.format("%.2f", decision.getExitTarget()));
        logger.info("  ├─ Stop Loss: {}", String.format("%.2f", decision.getStopLoss()));
        logger.info("═══════════════════════════════════════════════════════════════");

        // Store signals in database
        ExitSignal exitSignal = new ExitSignal();
        exitSignal.setPositionId(position.getId());
        exitSignal.setSymbol(symbol);
        exitSignal.setStrategySignal(strategyExitSignal);
        exitSignal.setStrategyConfidence(new BigDecimal(strategyConfidence));
        exitSignal.setIndicatorRecommendation(indicatorSignals.getRecommendation());
        exitSignal.setIndicatorConfidence(new BigDecimal(indicatorSignals.getConfidence()));
        exitSignal.setDynamicTarget(dynamicTarget.getDynamicTarget());
        exitSignal.setDynamicStop(dynamicTarget.getDynamicStop());
        exitSignal.setFinalAction(decision.getFinalAction());
        exitSignal.setCombinedConfidence(new BigDecimal(decision.getCombinedConfidence()));
        exitSignal.setGeneratedAt(LocalDateTime.now());
        exitSignalRepository.save(exitSignal);

        // Execute exit if action is EXIT
        if (decision.getFinalAction().equals("EXIT")) {
            executeExit(position, decision);
        }

        // Update position with latest target and stop
        position.setTargetPrice(decision.getExitTarget());
        position.setStopLossPrice(decision.getStopLoss());
        position.setLastSignalCheck(LocalDateTime.now());
        position.setSignalConfidence(new BigDecimal(decision.getCombinedConfidence()));
        positionRepository.save(position);
    }

    /**
     * LAYER 1: Get strategy-based exit signal
     */
    private String getStrategyExitSignal(Position position) {
        // Check strategy-specific exit conditions
        String strategy = position.getStrategy();
        BigDecimal profitPercent = position.getProfitPercent();
        BigDecimal lossPercent = position.getLossPercent();

        // IndexHunt: Exit at 2% profit or 2% loss
        if ("indexhunt".equalsIgnoreCase(strategy)) {
            if (profitPercent.compareTo(new BigDecimal("2.0")) >= 0) {
                return "PROFIT_TARGET_HIT";
            }
            if (lossPercent.compareTo(new BigDecimal("2.0")) <= -1) {
                return "STOP_LOSS_HIT";
            }
        }

        // ADV_CASH: 1.5% profit / 1.5% loss
        if ("adv_cash".equalsIgnoreCase(strategy)) {
            if (profitPercent.compareTo(new BigDecimal("1.5")) >= 0) {
                return "PROFIT_TARGET_HIT";
            }
            if (lossPercent.compareTo(new BigDecimal("1.5")) <= -1) {
                return "STOP_LOSS_HIT";
            }
        }

        return null;
    }

    /**
     * LAYER 2: Generate indicator-based exit signals
     */
    private IndicatorSignalResult generateIndicatorSignals(Position position, BigDecimal currentPrice) {
        IndicatorSignalResult result = new IndicatorSignalResult();

        // Get price history (last 50 bars)
        List<BigDecimal> priceHistory = indicatorService.getPriceHistory(position.getSymbol(), 50);
        List<BigDecimal> volumeHistory = indicatorService.getVolumeHistory(position.getSymbol(), 50);

        if (priceHistory.size() < 14) {
            result.setRecommendation("INSUFFICIENT_DATA");
            return result;
        }

        // Calculate indicators
        double rsi = indicatorService.calculateRSI(priceHistory, 14);
        double[] macd = indicatorService.calculateMACD(priceHistory);
        double atr = indicatorService.calculateATR(position.getSymbol(), 14);
        double[] bollinger = indicatorService.calculateBollingerBands(priceHistory, 20);
        double volumeRsi = indicatorService.calculateVolumeRSI(volumeHistory, 14);

        // Store indicators for charting
        indicatorService.storeIndicators(position.getSymbol(), rsi, macd[0], macd[1], macd[2],
            volumeRsi, atr, bollinger[0], bollinger[1], bollinger[2]);

        boolean isLong = position.getQuantity() > 0;

        // RSI Signals
        if (isLong && rsi > 70) {
            result.addSignal("RSI_OVERBOUGHT (RSI: " + String.format("%.2f", rsi) + ")");
            result.addSignalStrength((rsi - 70) / 30);
        }

        if (!isLong && rsi < 30) {
            result.addSignal("RSI_OVERSOLD (RSI: " + String.format("%.2f", rsi) + ")");
            result.addSignalStrength((30 - rsi) / 30);
        }

        // MACD Signals
        if (isLong && macd[2] < 0 && macd[0] < macd[1]) {
            result.addSignal("MACD_BEARISH_CROSSOVER");
            result.addSignalStrength(0.6);
        }

        if (!isLong && macd[2] > 0 && macd[0] > macd[1]) {
            result.addSignal("MACD_BULLISH_CROSSOVER");
            result.addSignalStrength(0.6);
        }

        // Bollinger Band Signals
        if (isLong && currentPrice.doubleValue() >= bollinger[0]) {
            result.addSignal("BB_UPPER_TOUCH");
            result.addSignalStrength(0.7);
        }

        if (!isLong && currentPrice.doubleValue() <= bollinger[2]) {
            result.addSignal("BB_LOWER_TOUCH");
            result.addSignalStrength(0.7);
        }

        // Volume Signals
        if (volumeRsi > 70 && rsi > 75) {
            result.addSignal("HIGH_VOLUME_EXIT");
            result.addSignalStrength(0.7);
        }

        // Calculate confidence and recommendation
        result.calculateConfidence();

        return result;
    }

    /**
     * LAYER 3: Calculate dynamic exit target
     */
    private DynamicTargetResult calculateDynamicTarget(Position position, BigDecimal currentPrice,
                                                      IndicatorSignalResult indicators) {
        DynamicTargetResult result = new DynamicTargetResult();

        // Get indicators
        double rsi = indicatorService.getLatestRSI(position.getSymbol());
        double[] macd = indicatorService.getLatestMACD(position.getSymbol());
        double atr = indicatorService.getLatestATR(position.getSymbol());

        BigDecimal entryPrice = position.getEntryPrice();
        boolean isLong = position.getQuantity() > 0;

        // Base target from strategy
        BigDecimal baseTarget = position.getTargetPrice();
        if (baseTarget == null) {
            baseTarget = isLong ?
                entryPrice.multiply(BigDecimal.valueOf(1.02)) :
                entryPrice.multiply(BigDecimal.valueOf(0.98));
        }

        // Volatility adjustment
        double volatilityFactor = 1 + (atr / entryPrice.doubleValue()) * 0.5;

        // Momentum adjustment
        double momentumFactor = 1 + (macd[2] / entryPrice.doubleValue()) * 0.1;

        // RSI adjustment (more aggressive in overbought/oversold)
        double rsiAdjustment = 1.0;
        if (isLong && rsi > 70) {
            rsiAdjustment = 1.0 - ((rsi - 70) / 30) * 0.2;  // Take profits earlier
        }

        // Calculate dynamic target
        double dynamicTargetValue = isLong ?
            baseTarget.doubleValue() * volatilityFactor * momentumFactor * rsiAdjustment :
            baseTarget.doubleValue() / (volatilityFactor * momentumFactor * rsiAdjustment);

        result.setDynamicTarget(new BigDecimal(dynamicTargetValue));

        // Dynamic stop loss
        double dynamicStopValue = isLong ?
            entryPrice.doubleValue() * (1.0 - atr / entryPrice.doubleValue()) :
            entryPrice.doubleValue() * (1.0 + atr / entryPrice.doubleValue());

        result.setDynamicStop(new BigDecimal(dynamicStopValue));
        result.setVolatilityFactor(volatilityFactor);
        result.setMomentumFactor(momentumFactor);
        result.setRsiAdjustment(rsiAdjustment);

        // Calculate profit potential and risk
        double profitPotential = Math.abs((dynamicTargetValue - currentPrice.doubleValue()) / currentPrice.doubleValue() * 100);
        double riskLevel = Math.abs((currentPrice.doubleValue() - dynamicStopValue) / currentPrice.doubleValue() * 100);

        result.setProfitPotential(profitPotential);
        result.setRiskLevel(riskLevel);

        return result;
    }

    /**
     * Make final hybrid exit decision
     */
    private HybridExitDecision makeHybridExitDecision(Position position, BigDecimal currentPrice,
                                                      String strategySignal, double strategyConfidence,
                                                      IndicatorSignalResult indicators,
                                                      DynamicTargetResult dynamicTarget) {
        HybridExitDecision decision = new HybridExitDecision();

        // Combined confidence
        double combinedConfidence = (strategyConfidence + indicators.getConfidence()) / 2;
        decision.setCombinedConfidence(combinedConfidence);

        // Final action determination
        String finalAction = "HOLD";

        // Priority 1: Strategy signal always takes precedence if high confidence
        if (strategySignal != null) {
            finalAction = "EXIT";
            combinedConfidence = Math.max(combinedConfidence, 0.85);
        }
        // Priority 2: Strong indicator signals
        else if (indicators.getRecommendation().equals("STRONG_EXIT") && combinedConfidence > 0.75) {
            finalAction = "EXIT";
        }
        // Priority 3: Weak indicator signals with high confidence
        else if (indicators.getRecommendation().equals("EXIT") && combinedConfidence > 0.60) {
            finalAction = "CONSIDER_EXIT";
        }

        decision.setFinalAction(finalAction);
        decision.setExitTarget(dynamicTarget.getDynamicTarget());
        decision.setStopLoss(dynamicTarget.getDynamicStop());

        return decision;
    }

    /**
     * Execute exit order
     */
    private void executeExit(Position position, HybridExitDecision decision) {
        try {
            logger.info("EXECUTING EXIT: {} Qty: {}", position.getSymbol(), position.getQuantity());

            // Send exit order to broker
            zerodhaAPI.placeMarketOrder(
                position.getSymbol(),
                -position.getQuantity(),  // Reverse quantity
                "BUY",
                "MIS",
                decision.getExitTarget()
            );

            // Update position status
            position.setStatus("CLOSED");
            position.setExitPrice(decision.getExitTarget());
            position.setExitTime(LocalDateTime.now());
            positionRepository.save(position);

            logger.info("EXIT EXECUTED SUCCESSFULLY: {} at {}", position.getSymbol(), decision.getExitTarget());

        } catch (Exception e) {
            logger.error("FAILED TO EXECUTE EXIT: {} - {}", position.getSymbol(), e.getMessage());
        }
    }

    // ─────────────────────────────────────── Helper Classes ───────────────────────────────────

    /**
     * Indicator Signal Result
     */
    static class IndicatorSignalResult {
        private List<String> signals = new ArrayList<>();
        private List<Double> signalStrengths = new ArrayList<>();
        private double confidence = 0.0;
        private String recommendation = "HOLD";

        public void addSignal(String signal) {
            signals.add(signal);
        }

        public void addSignalStrength(double strength) {
            signalStrengths.add(strength);
        }

        public void calculateConfidence() {
            if (signals.isEmpty()) {
                confidence = 0.0;
                recommendation = "HOLD";
                return;
            }

            confidence = Math.min(1.0, signalStrengths.stream().mapToDouble(Double::doubleValue).sum()
                / Math.max(signalStrengths.size(), 1));

            long exitSignalCount = signals.stream().filter(s -> s.contains("EXIT") || s.contains("OVERBOUGHT") || s.contains("OVERSOLD")).count();

            if (confidence > 0.75 && exitSignalCount > 1) {
                recommendation = "STRONG_EXIT";
            } else if (confidence > 0.6 && exitSignalCount > 0) {
                recommendation = "EXIT";
            } else if (confidence > 0.5) {
                recommendation = "WEAK_EXIT";
            }
        }

        public int getSignalCount() { return signals.size(); }
        public double getConfidence() { return confidence; }
        public String getRecommendation() { return recommendation; }
        public List<String> getSignals() { return signals; }
    }

    /**
     * Dynamic Target Result
     */
    static class DynamicTargetResult {
        private BigDecimal dynamicTarget;
        private BigDecimal dynamicStop;
        private double volatilityFactor;
        private double momentumFactor;
        private double rsiAdjustment;
        private double profitPotential;
        private double riskLevel;

        // Getters and setters
        public BigDecimal getDynamicTarget() { return dynamicTarget; }
        public void setDynamicTarget(BigDecimal value) { this.dynamicTarget = value; }

        public BigDecimal getDynamicStop() { return dynamicStop; }
        public void setDynamicStop(BigDecimal value) { this.dynamicStop = value; }

        public double getVolatilityFactor() { return volatilityFactor; }
        public void setVolatilityFactor(double value) { this.volatilityFactor = value; }

        public double getMomentumFactor() { return momentumFactor; }
        public void setMomentumFactor(double value) { this.momentumFactor = value; }

        public double getRsiAdjustment() { return rsiAdjustment; }
        public void setRsiAdjustment(double value) { this.rsiAdjustment = value; }

        public double getProfitPotential() { return profitPotential; }
        public void setProfitPotential(double value) { this.profitPotential = value; }

        public double getRiskLevel() { return riskLevel; }
        public void setRiskLevel(double value) { this.riskLevel = value; }
    }

    /**
     * Hybrid Exit Decision
     */
    static class HybridExitDecision {
        private String finalAction = "HOLD";
        private BigDecimal exitTarget;
        private BigDecimal stopLoss;
        private double combinedConfidence = 0.0;

        public String getFinalAction() { return finalAction; }
        public void setFinalAction(String action) { this.finalAction = action; }

        public BigDecimal getExitTarget() { return exitTarget; }
        public void setExitTarget(BigDecimal target) { this.exitTarget = target; }

        public BigDecimal getStopLoss() { return stopLoss; }
        public void setStopLoss(BigDecimal loss) { this.stopLoss = loss; }

        public double getCombinedConfidence() { return combinedConfidence; }
        public void setCombinedConfidence(double conf) { this.combinedConfidence = conf; }
    }
}
