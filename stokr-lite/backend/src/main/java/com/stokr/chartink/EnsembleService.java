package com.stokr.chartink;

import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Ensemble Meta-Strategy (Strategy H from the NSE document).
 * Collects all signals in a 5-minute window and computes composite score.
 * Only trades highest-conviction signals (score > 70 long, < 30 short).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnsembleService {

    private final SignalRepository signalRepository;

    // Weights from the NSE document + STOKR_ prefixed scanners
    private static final Map<String, Double> SCANNER_WEIGHTS = Map.ofEntries(
            // Legacy names
            Map.entry("PREOPEN_UNFILLED", 0.20),
            Map.entry("PREOPEN_IMBALANCE", 0.15),
            Map.entry("VWAP_DEVIATION", 0.10),
            Map.entry("GAP_UP", 0.10),
            Map.entry("GAP_DOWN", 0.10),
            Map.entry("ORB_BREAKOUT", 0.15),
            Map.entry("VOLUME_SPIKE", 0.10),
            Map.entry("BUYER_SELLER_IMBALANCE", 0.10),
            Map.entry("OFI_PROXY", 0.10),
            // STOKR_ prefixed scanner names (Chartink)
            Map.entry("STOKR_VWAP_TRIPLE_LONG", 0.15),
            Map.entry("STOKR_TRADE_BOOK_IMBALANCE", 0.15),
            Map.entry("STOKR_ORB_V_BREAKOUT", 0.20),
            Map.entry("STOKR_MORNING_SURGE_SHORT", 0.15),
            Map.entry("STOKR_PRE_OPEN_BUY", 0.20)
    );

    /**
     * Compute ensemble score for a symbol based on all recent signals.
     * Looks back 5 minutes from now.
     */
    public EnsembleResult computeScore(String symbol, Instant now) {
        Instant windowStart = now.minusSeconds(300); // 5 min window
        List<SignalEntity> recentSignals = signalRepository
                .findTop50ByUserIdOrUserIdIsNullOrderByCreatedAtDesc(null)
                .stream()
                .filter(s -> s.getSymbol().equals(symbol))
                .filter(s -> s.getCreatedAt().isAfter(windowStart))
                .toList();

        if (recentSignals.isEmpty()) {
            return new EnsembleResult(50.0, false, "NO_SIGNALS", Map.of());
        }

        double bullishScore = 0;
        double bearishScore = 0;
        Map<String, Double> breakdown = new java.util.HashMap<>();

        for (SignalEntity s : recentSignals) {
            String scanner = s.getScannerName() != null ? s.getScannerName().toUpperCase() : "UNKNOWN";
            double weight = SCANNER_WEIGHTS.getOrDefault(scanner, 0.05);
            double movement = s.getMovementScore() != null ? s.getMovementScore() : 50;
            double contribution = weight * movement;

            if (s.getSide() == SignalEntity.Side.BUY) {
                bullishScore += contribution;
            } else {
                bearishScore += contribution;
            }

            breakdown.merge(scanner, contribution, Double::sum);
        }

        double netScore = bullishScore - bearishScore + 50; // center at 50
        netScore = Math.max(0, Math.min(100, netScore));

        String decision;
        boolean shouldTrade = false;
        if (netScore > 70) {
            decision = "LONG";
            shouldTrade = true;
        } else if (netScore < 30) {
            decision = "SHORT";
            shouldTrade = true;
        } else {
            decision = "NEUTRAL";
        }

        return new EnsembleResult(netScore, shouldTrade, decision, breakdown);
    }

    /**
     * Position sizing based on ensemble confidence.
     * quarter-Kelly capped at 10% of capital per trade.
     */
    public double positionSizePct(double ensembleScore) {
        double confidence = Math.abs(ensembleScore - 50) / 50; // 0 to 1
        double winRate = 0.55; // assumed from backtest
        double avgWin = 1.8;   // R multiples
        double avgLoss = 1.0;
        double kelly = (winRate * avgWin - (1 - winRate) * avgLoss) / avgWin;
        return Math.min(kelly / 4, 0.10) * confidence;
    }

    public record EnsembleResult(
            double score,
            boolean shouldTrade,
            String decision,
            Map<String, Double> breakdown
    ) {}
}
