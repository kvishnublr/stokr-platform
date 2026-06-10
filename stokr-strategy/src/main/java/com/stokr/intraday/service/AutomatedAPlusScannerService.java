package com.stokr.intraday.service;

import com.stokr.intraday.domain.APlusStrategyConfig;
import com.stokr.intraday.domain.AutomatedAPlusTrade;
import com.stokr.intraday.repository.APlusStrategyConfigRepository;
import com.stokr.intraday.repository.AutomatedAPlusTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomatedAPlusScannerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AutomatedAPlusTradeRepository tradeRepository;
    private final APlusStrategyConfigRepository configRepository;
    private final UnifiedSignalTruthService signalTruthService;
    private final AutomatedAPlusTradeEntryService entryService;

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
                return;
            }

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
                        BigDecimal ltp = (BigDecimal) row.get("ltp");
                        if (ltp != null) {
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
}
