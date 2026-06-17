package com.stokr.filter;

import com.stokr.chartink.ChartinkPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified Movement Assurance Layer.
 * Combines all 6 filters into a single score. Trade only when score > 70.
 */
@Slf4j
@Component
public class MovementAssuranceFilter {

    public record MovementResult(
            double score,
            boolean pass,
            List<String> failedFilters,
            Map<String, Double> componentScores
    ) {}

    private static final Map<String, Double> WEIGHTS = Map.of(
            "volatility", 0.20,
            "volume",     0.20,
            "spread",     0.15,
            "microPrice", 0.20,
            "regime",     0.15,
            "indexAlign", 0.10
    );

    /**
     * Target percentage default: 0.4% for NSE cash intraday.
     */
    private static final BigDecimal DEFAULT_TARGET_PCT = new BigDecimal("0.004");

    public MovementResult evaluate(ChartinkPayload p) {
        List<String> failures = new ArrayList<>();
        Map<String, Double> scores = new java.util.HashMap<>();

        MicroPriceFilter.Direction dir = "BUY".equals(p.inferSide())
                ? MicroPriceFilter.Direction.LONG
                : MicroPriceFilter.Direction.SHORT;

        String strategyType = RegimeFilter.classifyStrategy(p.scannerName());

        // 1. Volatility (20%)
        double volScore = VolatilityFilter.score(p.atr14(), DEFAULT_TARGET_PCT);
        scores.put("volatility", volScore);
        if (volScore < 50) failures.add("volatility");

        // 2. Relative Volume (20%)
        double vol2Score = VolumeFilter.score(p.rvol());
        scores.put("volume", vol2Score);
        if (vol2Score < 40) failures.add("volume");

        // 3. Spread (15%) — use category-based defaults if no bid/ask
        double spreadScore;
        if (p.bestBid() != null && p.bestAsk() != null) {
            spreadScore = SpreadFilter.score(p.bestBid(), p.bestAsk(), p.stockCategory());
        } else {
            // No order book data — assume acceptable for NIFTY 50
            spreadScore = "NIFTY50".equalsIgnoreCase(p.stockCategory()) ? 80 : 60;
        }
        scores.put("spread", spreadScore);
        if (spreadScore < 50) failures.add("spread");

        // 4. MicroPrice (20%) — use buyer/seller ratio from Chartink
        double mpScore = MicroPriceFilter.score(p.buyerQty(), p.sellerQty(), dir);
        scores.put("microPrice", mpScore);
        if (mpScore < 50) failures.add("microPrice");

        // 5. Regime ADX (15%)
        double regimeScore = RegimeFilter.score(p.adx14(), strategyType);
        scores.put("regime", regimeScore);
        if (regimeScore < 50) failures.add("regime");

        // 6. Index alignment (10%)
        BigDecimal niftyChange = null;
        try {
            if (p.niftyChangePct() != null) {
                niftyChange = new BigDecimal(p.niftyChangePct().replace("%", "").trim());
            }
        } catch (Exception e) {
            log.debug("Could not parse niftyChangePct: {}", p.niftyChangePct());
        }
        IndexAlignmentFilter.Direction indexDir = "BUY".equals(p.inferSide())
                ? IndexAlignmentFilter.Direction.LONG
                : IndexAlignmentFilter.Direction.SHORT;
        double indexScore = IndexAlignmentFilter.score(p.changePct(), niftyChange, indexDir);
        scores.put("indexAlign", indexScore);
        if (indexScore < 50) failures.add("indexAlign");

        // Weighted total
        double total = 0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            total += e.getValue() * WEIGHTS.getOrDefault(e.getKey(), 0.0);
        }

        return new MovementResult(
                Math.round(total * 100.0) / 100.0,
                total > 70,
                failures,
                scores
        );
    }
}
