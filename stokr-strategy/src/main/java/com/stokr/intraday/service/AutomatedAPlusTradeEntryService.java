package com.stokr.intraday.service;

import com.stokr.intraday.domain.APlusStrategyConfig;
import com.stokr.intraday.domain.AutomatedAPlusTrade;
import com.stokr.intraday.repository.AutomatedAPlusTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomatedAPlusTradeEntryService {

    private final AutomatedAPlusTradeRepository tradeRepository;

    @Transactional
    public AutomatedAPlusTrade createTradeEntry(
            String symbol,
            BigDecimal entryPrice,
            Integer aiScore,
            String side,
            APlusStrategyConfig config) {
        try {
            AutomatedAPlusTrade trade = AutomatedAPlusTrade.builder()
                    .symbol(symbol)
                    .entryPrice(entryPrice)
                    .entryTime(Instant.now())
                    .entryAiScore(aiScore)
                    .currentAiScore(aiScore)
                    .side(side)
                    .quantity(config.getPositionSizeQty())
                    .status("ACTIVE")
                    .strategyName("AI_PLUS_SETUP_AUTO")
                    .aiScoreDropReason(false)
                    .oppositeSignalTriggered(false)
                    .hardSlHit(false)
                    .hardTpHit(false)
                    .marketCloseExit(false)
                    .build();

            trade = tradeRepository.save(trade);
            log.info("✅ A+ ENTRY: {} {} qty={} @ {} (aiScore: {}, Trade ID: {})",
                    side, symbol, config.getPositionSizeQty(), entryPrice, aiScore, trade.getId());
            return trade;

        } catch (Exception e) {
            log.error("Failed to create trade entry for {}", symbol, e);
            return null;
        }
    }
}
