package com.stokr.intraday.controller;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.service.SetupDetectionService;
import com.stokr.intraday.stream.RealTimeSetupStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST API for setup detection
 * Provides endpoints for trader terminal and dashboard
 */
@RestController
@RequestMapping("/api/v1/intraday/setups")
@Slf4j
public class SetupDetectionController {

    private final SetupDetectionService detectionService;
    private final RealTimeSetupStream realTimeStream;

    public SetupDetectionController(
            SetupDetectionService detectionService,
            RealTimeSetupStream realTimeStream) {
        this.detectionService = detectionService;
        this.realTimeStream = realTimeStream;
    }

    /**
     * Get ranking board (top 12 setups)
     * Used by Market Pulse dashboard
     *
     * GET /api/v1/intraday/setups/ranking-board
     */
    @GetMapping("/ranking-board")
    public ResponseEntity<RankingBoardResponse> getRankingBoard() {
        try {
            List<CurrentSetup> board = realTimeStream.getRankingBoard();

            RankingBoardResponse response = new RankingBoardResponse();
            response.regime = realTimeStream.getCurrentRegime();
            response.setups = board;
            response.totalCount = board.size();
            response.topScore = board.isEmpty() ? BigDecimal.ZERO : board.get(0).getQualityScore();

            log.debug("controller.ranking_board count={}", board.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("controller.ranking_board_error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get setups by type
     * Used by Setup Playbook dashboard
     *
     * GET /api/v1/intraday/setups/by-type?type=gap_fill
     */
    @GetMapping("/by-type")
    public ResponseEntity<List<CurrentSetup>> getSetupsByType(@RequestParam String type) {
        try {
            List<CurrentSetup> setups = realTimeStream.getRankingBoard().stream()
                    .filter(setup -> setup.getSetupType().equals(type))
                    .toList();

            log.debug("controller.by_type type={} count={}", type, setups.size());
            return ResponseEntity.ok(setups);
        } catch (Exception e) {
            log.error("controller.by_type_error type={}", type, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get setups by confidence level
     * Used for filtering (HIGH, MEDIUM, LOW)
     *
     * GET /api/v1/intraday/setups/by-confidence?level=HIGH
     */
    @GetMapping("/by-confidence")
    public ResponseEntity<List<CurrentSetup>> getSetupsByConfidence(@RequestParam String level) {
        try {
            List<CurrentSetup> setups = realTimeStream.getRankingBoard().stream()
                    .filter(setup -> setup.getConfidenceLevel() != null &&
                            setup.getConfidenceLevel().equals(level))
                    .toList();

            log.debug("controller.by_confidence level={} count={}", level, setups.size());
            return ResponseEntity.ok(setups);
        } catch (Exception e) {
            log.error("controller.by_confidence_error level={}", level, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get setups by minimum quality score
     * Used for filtering high-quality opportunities
     *
     * GET /api/v1/intraday/setups/by-quality?minScore=70
     */
    @GetMapping("/by-quality")
    public ResponseEntity<List<CurrentSetup>> getSetupsByQuality(@RequestParam BigDecimal minScore) {
        try {
            List<CurrentSetup> setups = realTimeStream.getRankingBoard().stream()
                    .filter(setup -> setup.getQualityScore() != null &&
                            setup.getQualityScore().compareTo(minScore) >= 0)
                    .toList();

            log.debug("controller.by_quality minScore={} count={}", minScore, setups.size());
            return ResponseEntity.ok(setups);
        } catch (Exception e) {
            log.error("controller.by_quality_error minScore={}", minScore, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get setup details by stock
     * Used for detailed analysis in Setup Playbook
     *
     * GET /api/v1/intraday/setups/stock/INFY
     */
    @GetMapping("/stock/{stockId}")
    public ResponseEntity<List<CurrentSetup>> getSetupsByStock(@PathVariable String stockId) {
        try {
            List<CurrentSetup> setups = realTimeStream.getRankingBoard().stream()
                    .filter(setup -> setup.getStockId().equals(stockId))
                    .toList();

            log.debug("controller.by_stock stock={} count={}", stockId, setups.size());
            return ResponseEntity.ok(setups);
        } catch (Exception e) {
            log.error("controller.by_stock_error stock={}", stockId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get market regime and stream statistics
     * Used by dashboard header/status bar
     *
     * GET /api/v1/intraday/setups/status
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> getStatus() {
        try {
            RealTimeSetupStream.StreamStatistics stats = realTimeStream.getStatistics();

            StatusResponse response = new StatusResponse();
            response.regime = stats.regime;
            response.regimeDescription = getRegimeDescription(stats.regime);
            response.activeSetups = stats.rankingBoardSize;
            response.topScore = stats.topSetupScore;
            response.subscribers = stats.listenerCount;

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("controller.status_error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get setup type statistics
     * Used for performance analysis by setup type
     *
     * GET /api/v1/intraday/setups/stats/by-type
     */
    @GetMapping("/stats/by-type")
    public ResponseEntity<Map<String, TypeStats>> getTypeStatistics() {
        try {
            Map<String, TypeStats> stats = new java.util.HashMap<>();

            List<CurrentSetup> board = realTimeStream.getRankingBoard();

            for (String type : new String[]{"gap_fill", "vwap_bounce", "sector_laggard", "early_breakout"}) {
                List<CurrentSetup> typeSetups = board.stream()
                        .filter(s -> s.getSetupType().equals(type))
                        .toList();

                TypeStats typeStat = new TypeStats();
                typeStat.type = type;
                typeStat.count = typeSetups.size();
                typeStat.avgScore = typeSetups.isEmpty() ? BigDecimal.ZERO :
                        typeSetups.stream()
                                .map(CurrentSetup::getQualityScore)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(new java.math.BigDecimal(typeSetups.size()), 2, java.math.RoundingMode.HALF_UP);
                typeStat.topScore = typeSetups.isEmpty() ? BigDecimal.ZERO : typeSetups.get(0).getQualityScore();

                stats.put(type, typeStat);
            }

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("controller.type_stats_error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get regime description for UI display
     */
    private String getRegimeDescription(MarketRegimeDetector.MarketRegime regime) {
        return switch (regime) {
            case TRENDING_UP -> "Strong Uptrend - Bullish setups favored";
            case TRENDING_DOWN -> "Strong Downtrend - Defensive setups";
            case CHOPPY -> "Range-bound - Low confidence";
            case VOLATILE -> "High volatility - Reduced probability";
            case QUIET -> "Low volume - Limited opportunities";
        };
    }

    /**
     * Response DTOs
     */
    public static class RankingBoardResponse {
        public MarketRegimeDetector.MarketRegime regime;
        public List<CurrentSetup> setups;
        public int totalCount;
        public BigDecimal topScore;
    }

    public static class StatusResponse {
        public MarketRegimeDetector.MarketRegime regime;
        public String regimeDescription;
        public int activeSetups;
        public BigDecimal topScore;
        public int subscribers;
    }

    public static class TypeStats {
        public String type;
        public int count;
        public BigDecimal avgScore;
        public BigDecimal topScore;
    }
}
