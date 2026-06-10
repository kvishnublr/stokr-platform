package com.stokr.intraday.service;

import com.stokr.intraday.domain.APlusStrategyConfig;
import com.stokr.intraday.domain.AutomatedAPlusTrade;
import com.stokr.intraday.repository.APlusStrategyConfigRepository;
import com.stokr.intraday.repository.AutomatedAPlusTradeRepository;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomatedAPlusExitService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AutomatedAPlusTradeRepository tradeRepository;
    private final APlusStrategyConfigRepository configRepository;
    private final MarketDataQueryService marketDataQueryService;
    private final UnifiedSignalTruthService signalTruthService;

    /**
     * Monitor active trades for exit conditions every 10 seconds
     */
    @Scheduled(fixedDelayString = "${stokr.a-plus.exit-check-interval-ms:10000}")
    public void monitorAndExitTrades() {
        try {
            APlusStrategyConfig config = configRepository.findByIdAndEnabledTrue(1L)
                    .orElse(null);
            if (config == null || !config.getEnabled()) {
                return;
            }

            List<AutomatedAPlusTrade> activeTrades = tradeRepository.findActiveTradesOrderByRecent();
            Map<String, Object> terminal = signalTruthService.buildTerminal(null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) terminal.get("scannerRows");

            for (AutomatedAPlusTrade trade : activeTrades) {
                checkAndExitTrade(trade, config, rows);
            }

        } catch (Exception e) {
            log.error("Error monitoring exits", e);
        }
    }

    @Transactional
    private void checkAndExitTrade(
            AutomatedAPlusTrade trade,
            APlusStrategyConfig config,
            List<Map<String, Object>> currentRows) {

        try {
            // Check market close
            LocalTime now = LocalTime.now(IST);
            LocalTime marketClose = LocalTime.of(config.getMarketCloseHour(), config.getMarketCloseMinute());
            if (now.isAfter(marketClose) && config.getAutoCloseMarketEnd()) {
                exitTrade(trade, "MARKET_CLOSE", null, "Market close auto-exit");
                return;
            }

            // Get current aiScore from terminal
            Integer currentAiScore = findCurrentAiScore(trade.getSymbol(), currentRows);
            if (currentAiScore != null) {
                trade.setCurrentAiScore(currentAiScore);
            }

            // Check AI score drop (exit when drops below threshold)
            if (currentAiScore != null) {
                int scoreDecay = trade.getEntryAiScore() - currentAiScore;
                log.debug("A+ Monitor: {} aiScore: {} → {} (decay: {}, threshold: {})",
                        trade.getSymbol(), trade.getEntryAiScore(), currentAiScore, scoreDecay,
                        config.getExitAiScoreThreshold());

                if (currentAiScore < config.getExitAiScoreThreshold()) {
                    exitTrade(trade, "AI_SCORE_DROP", null,
                            String.format("✅ AI_SCORE_DROP: aiScore %.0f→%.0f (below threshold %d)",
                                    (float)trade.getEntryAiScore(), (float)currentAiScore,
                                    config.getExitAiScoreThreshold()));
                    trade.setAiScoreDropReason(true);
                    return;
                }
            } else {
                log.debug("A+ Monitor: {} aiScore not found in current terminal", trade.getSymbol());
            }

            // Check opposite signal
            String oppositeSignal = "BUY".equals(trade.getSide()) ? "SELL" : "BUY";
            boolean hasOppositeSignal = currentRows.stream()
                    .anyMatch(r -> trade.getSymbol().equals(String.valueOf(r.get("symbol")))
                            && oppositeSignal.equals(String.valueOf(r.get("side")))
                            && (Integer) r.getOrDefault("aiScore", 0) >= 85);
            if (hasOppositeSignal) {
                exitTrade(trade, "OPPOSITE_SIGNAL", null, "Opposite A+ signal detected");
                trade.setOppositeSignalTriggered(true);
                return;
            }

            // Check hard SL/TP
            BigDecimal currentPrice = getCurrentPrice(trade.getSymbol());
            if (currentPrice != null) {
                checkHardStops(trade, currentPrice, config);
            }

        } catch (Exception e) {
            log.error("Error checking exit for trade {}", trade.getId(), e);
        }
    }

    @Transactional
    private void checkHardStops(
            AutomatedAPlusTrade trade,
            BigDecimal currentPrice,
            APlusStrategyConfig config) {

        BigDecimal pnlPct = calculatePnLPct(trade.getEntryPrice(), currentPrice, trade.getSide());

        // Check TP: +3%
        if (pnlPct.compareTo(config.getHardTpPct()) >= 0) {
            exitTrade(trade, "HARD_TP", currentPrice,
                    String.format("Profit target hit: +%.2f%%", pnlPct));
            trade.setHardTpHit(true);
            return;
        }

        // Check SL: -1.5%
        if (pnlPct.compareTo(config.getHardSlPct().negate()) <= 0) {
            exitTrade(trade, "HARD_SL", currentPrice,
                    String.format("Stop loss hit: %.2f%%", pnlPct));
            trade.setHardSlHit(true);
            return;
        }
    }

    @Transactional
    private void exitTrade(
            AutomatedAPlusTrade trade,
            String exitReason,
            BigDecimal exitPrice,
            String message) {

        if ("EXITED".equals(trade.getStatus()) || "EXIT_PENDING".equals(trade.getStatus())) {
            return;
        }

        try {
            if (exitPrice == null) {
                exitPrice = getCurrentPrice(trade.getSymbol());
            }

            BigDecimal pnl = null;
            BigDecimal pnlPct = null;

            if (exitPrice != null) {
                pnl = calculatePnL(trade.getEntryPrice(), exitPrice, trade.getQuantity(), trade.getSide());
                pnlPct = calculatePnLPct(trade.getEntryPrice(), exitPrice, trade.getSide());
            }

            trade.setExitPrice(exitPrice);
            trade.setExitTime(Instant.now());
            trade.setStatus("EXITED");
            trade.setExitTrigger(exitReason);
            trade.setPnl(pnl);
            trade.setPnlPct(pnlPct);

            tradeRepository.save(trade);

            log.info("✅ A+ EXIT: {} {} @ {} | PnL: {} ({}) | Reason: {}",
                    trade.getSide(), trade.getSymbol(), exitPrice,
                    pnl != null ? pnl.setScale(2, RoundingMode.HALF_UP) : "N/A",
                    pnlPct != null ? pnlPct.setScale(2, RoundingMode.HALF_UP) + "%" : "N/A",
                    message);

        } catch (Exception e) {
            log.error("Error exiting trade {}", trade.getId(), e);
        }
    }

    private Integer findCurrentAiScore(String symbol, List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(r -> symbol.equals(String.valueOf(r.get("symbol"))))
                .map(r -> (Integer) r.get("aiScore"))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal getCurrentPrice(String symbol) {
        try {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(
                    symbol.contains("NSE:") ? symbol : "NSE:" + symbol, "1m", 1);
            if (!bars.isEmpty()) {
                return bars.get(bars.size() - 1).getClosePrice();
            }
        } catch (Exception e) {
            log.debug("Could not get current price for {}", symbol, e);
        }
        return null;
    }

    private BigDecimal calculatePnL(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            Integer quantity,
            String side) {
        if (entryPrice == null || exitPrice == null || quantity == null) {
            return null;
        }

        BigDecimal priceDiff = exitPrice.subtract(entryPrice);
        if ("SELL".equals(side)) {
            priceDiff = priceDiff.negate();
        }

        return priceDiff.multiply(BigDecimal.valueOf(quantity))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePnLPct(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            String side) {
        if (entryPrice == null || exitPrice == null || entryPrice.signum() == 0) {
            return null;
        }

        BigDecimal pnlPct = exitPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if ("SELL".equals(side)) {
            pnlPct = pnlPct.negate();
        }

        return pnlPct.setScale(4, RoundingMode.HALF_UP);
    }
}
