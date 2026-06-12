package com.stokr.intraday.service;

import com.stokr.intraday.domain.APlusStrategyConfig;
import com.stokr.intraday.domain.AutomatedAPlusTrade;
import com.stokr.intraday.repository.APlusStrategyConfigRepository;
import com.stokr.intraday.repository.AutomatedAPlusTradeRepository;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomatedAPlusScannerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Reject entries when scanner LTP deviates this much from the latest candle close. */
    private static final double MAX_LTP_CANDLE_DEVIATION_PCT = 5.0;

    private final AutomatedAPlusTradeRepository tradeRepository;
    private final APlusStrategyConfigRepository configRepository;
    private final UnifiedSignalTruthService signalTruthService;
    private final AutomatedAPlusTradeEntryService entryService;
    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.a-plus.post-exit-cooldown-minutes:15}")
    private int postExitCooldownMinutes;

    /**
     * Scans every 30 seconds for A+ setups and creates trades
     */
    @Scheduled(fixedDelayString = "${stokr.a-plus.scan-interval-ms:30000}")
    public void scanAndTradeAPlusSetups() {
        try {
            APlusStrategyConfig config = configRepository.findByIdAndEnabledTrue(1L)
                    .orElse(null);
            if (config == null || !config.getEnabled()) {
                log.debug("A+ strategy disabled");
                return;
            }

            // Check market hours
            LocalTime now = LocalTime.now(IST);
            LocalTime marketOpen = LocalTime.of(9, 15);
            LocalTime marketClose = LocalTime.of(15, 30);
            if (now.isBefore(marketOpen) || now.isAfter(marketClose)) {
                return;
            }

            // Check concurrent position limit
            long activeCount = tradeRepository.countActivePositions();
            if (activeCount >= config.getMaxConcurrentPositions()) {
                log.debug("Max concurrent positions ({}) reached", config.getMaxConcurrentPositions());
                return;
            }

            // Get all scanner rows from terminal
            Map<String, Object> terminal = signalTruthService.buildTerminal(null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) terminal.get("scannerRows");

            if (rows == null || rows.isEmpty()) {
                log.debug("A+ Scanner: No scanner rows found in terminal");
                return;
            }

            log.info("A+ Scanner: Found {} total rows to scan", rows.size());

            // Count A+ setups in this scan
            int aplusCount = 0;
            for (Map<String, Object> row : rows) {
                int aiScore = (Integer) row.getOrDefault("aiScore", 0);
                if (aiScore >= config.getEntryAiScoreMin()) {
                    aplusCount++;
                }
            }
            log.info("A+ Scanner: {} A+ setups detected (threshold: {})", aplusCount, config.getEntryAiScoreMin());

            int entriesCreated = 0;
            for (Map<String, Object> row : rows) {
                if (entriesCreated >= (config.getMaxConcurrentPositions() - activeCount)) {
                    break;
                }

                int aiScore = (Integer) row.getOrDefault("aiScore", 0);
                String symbol = String.valueOf(row.get("symbol"));
                String side = String.valueOf(row.getOrDefault("side", "BUY"));

                // Entry criteria: aiScore >= 85
                if (aiScore >= config.getEntryAiScoreMin()) {
                    // Check if already have active position
                    Optional<AutomatedAPlusTrade> existing = tradeRepository.findActiveTradeBySymbol(symbol);
                    if (existing.isEmpty()) {
                        if (isInPostExitCooldown(symbol)) {
                            continue;
                        }
                        BigDecimal ltp = (BigDecimal) row.get("ltp");
                        if (ltp != null && isEntryPriceSane(symbol, ltp)) {
                            AutomatedAPlusTrade trade = entryService.createTradeEntry(
                                    symbol, ltp, aiScore, side, config);
                            if (trade != null) {
                                log.info("✅ A+ ENTRY: {} {} @ {} (aiScore: {})",
                                        side, symbol, ltp, aiScore);
                                entriesCreated++;
                            }
                        }
                    }
                }
            }

            if (entriesCreated > 0) {
                log.info("A+ Scanner: Created {} new trade entries", entriesCreated);
            }

        } catch (Exception e) {
            log.error("Error in A+ scanner", e);
        }
    }

    /**
     * Block re-entry on a symbol right after exiting it — the entry and opposite-signal
     * logic otherwise flip-flop the same symbol within seconds, paying charges each time.
     */
    private boolean isInPostExitCooldown(String symbol) {
        if (postExitCooldownMinutes <= 0) {
            return false;
        }
        Instant since = Instant.now().minus(postExitCooldownMinutes, ChronoUnit.MINUTES);
        boolean cooling = tradeRepository.existsExitedSince(symbol, since);
        if (cooling) {
            log.debug("A+ Scanner: {} in post-exit cooldown ({} min)", symbol, postExitCooldownMinutes);
        }
        return cooling;
    }

    /**
     * Reject entries whose scanner LTP does not match the symbol's real candle close —
     * catches corrupt scanner rows (e.g. option premium against an equity symbol, or
     * symbols with no market data at all such as delisted names).
     */
    private boolean isEntryPriceSane(String symbol, BigDecimal ltp) {
        String plainSymbol = symbol.startsWith("NSE:") ? symbol.substring(4) : symbol;
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(plainSymbol, "1m", 1);
        if (bars.isEmpty() || bars.get(bars.size() - 1).getClosePrice() == null) {
            log.warn("A+ Scanner: rejecting {} — no market data candles to validate ltp={}", symbol, ltp);
            return false;
        }
        BigDecimal candleClose = bars.get(bars.size() - 1).getClosePrice();
        if (candleClose.signum() <= 0) {
            return false;
        }
        double deviationPct = ltp.subtract(candleClose).abs()
                .divide(candleClose, 6, java.math.RoundingMode.HALF_UP)
                .doubleValue() * 100.0;
        if (deviationPct > MAX_LTP_CANDLE_DEVIATION_PCT) {
            log.warn("A+ Scanner: rejecting {} — scanner ltp={} deviates {}% from candle close={}",
                    symbol, ltp, String.format("%.1f", deviationPct), candleClose);
            return false;
        }
        return true;
    }
}
