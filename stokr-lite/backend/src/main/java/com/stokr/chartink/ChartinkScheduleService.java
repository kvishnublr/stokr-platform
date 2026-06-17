package com.stokr.chartink;

import com.stokr.engine.SignalRepository;
import com.stokr.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Scheduled jobs for Chartink position management:
 * - Trailing stop updates (every 30 seconds)
 * - Time-based exits (2:00 PM mean reversion, 3:15 PM EOD)
 * - Hard stop loss and target checks
 * Supports per-strategy exit rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartinkScheduleService {

    private final ChartinkPositionRepository positionRepository;
    private final ChartinkExecutionService executionService;
    private final MarketDataService marketDataService;
    private final SignalRepository signalRepository;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MEAN_REVERSION_CUTOFF = LocalTime.of(14, 0);
    private static final LocalTime MOMENTUM_CUTOFF = LocalTime.of(15, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    /**
     * Every 30 seconds: update trailing stops and check hard SL / target.
     */
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void monitorPositions() {
        var openPositions = positionRepository.findByStatusOrderByCreatedAtDesc("OPEN");
        if (openPositions.isEmpty()) return;

        LocalTime now = LocalTime.now(IST);

        for (ChartinkPosition pos : openPositions) {
            try {
                BigDecimal ltp = marketDataService.getLtp(pos.getSymbol());
                if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

                // Update highest/lowest
                pos.updateHighestLowest(ltp);

                // Calculate unrealized PnL
                BigDecimal pnl = ltp.subtract(pos.getAvgPrice())
                        .multiply(BigDecimal.valueOf(
                                "BUY".equals(pos.getSide()) ? pos.getQuantity() : -pos.getQuantity()));
                pos.setUnrealizedPnl(pnl);

                // Check hard stop loss
                boolean slHit = "BUY".equals(pos.getSide())
                        ? ltp.compareTo(pos.getStopLoss()) <= 0
                        : ltp.compareTo(pos.getStopLoss()) >= 0;
                if (slHit) {
                    log.info("Chartink: HARD SL hit for {} @ {} (SL={})",
                            pos.getSymbol(), ltp, pos.getStopLoss());
                    executionService.closePosition(pos.getSymbol(), "HARD_STOP_LOSS");
                    continue;
                }

                // Check target
                boolean targetHit = "BUY".equals(pos.getSide())
                        ? ltp.compareTo(pos.getTarget()) >= 0
                        : ltp.compareTo(pos.getTarget()) <= 0;
                if (targetHit) {
                    log.info("Chartink: TARGET hit for {} @ {} (Target={})",
                            pos.getSymbol(), ltp, pos.getTarget());
                    executionService.closePosition(pos.getSymbol(), "TARGET_HIT");
                    continue;
                }

                // Update trailing stop (0.5 ATR from extreme)
                updateTrailingStop(pos, ltp);

                // Check trailing stop
                boolean tslHit = "BUY".equals(pos.getSide())
                        ? ltp.compareTo(pos.getTrailingStop()) <= 0
                        : ltp.compareTo(pos.getTrailingStop()) >= 0;
                if (tslHit && pos.getTrailingStop() != null
                        && pos.getTrailingStop().compareTo(pos.getStopLoss()) != 0) {
                    log.info("Chartink: TRAILING SL hit for {} @ {} (TSL={})",
                            pos.getSymbol(), ltp, pos.getTrailingStop());
                    executionService.closePosition(pos.getSymbol(), "TRAILING_STOP");
                    continue;
                }

                // Time-based exits per strategy
                checkTimeExit(pos, now);

                positionRepository.save(pos);

            } catch (Exception e) {
                log.error("Chartink: Error monitoring position {}", pos.getSymbol(), e);
            }
        }
    }

    private void updateTrailingStop(ChartinkPosition pos, BigDecimal ltp) {
        if (pos.getAvgPrice() == null || pos.getStopLoss() == null) return;

        // ATR-based trailing: 0.5 * |entry - SL| from extreme
        BigDecimal atr = pos.getAvgPrice().subtract(pos.getStopLoss()).abs();
        BigDecimal trailDistance = atr.multiply(new BigDecimal("0.5"));

        if ("BUY".equals(pos.getSide())) {
            BigDecimal newTsl = pos.getHighestPrice().subtract(trailDistance);
            if (pos.getTrailingStop() == null || newTsl.compareTo(pos.getTrailingStop()) > 0) {
                pos.setTrailingStop(newTsl);
            }
        } else {
            BigDecimal newTsl = pos.getLowestPrice().add(trailDistance);
            if (pos.getTrailingStop() == null || newTsl.compareTo(pos.getTrailingStop()) < 0) {
                pos.setTrailingStop(newTsl);
            }
        }
    }

    private void checkTimeExit(ChartinkPosition pos, LocalTime now) {
        String scanner = getScannerName(pos.getSignalId());

        // After 2:00 PM: close mean-reversion strategies (VWAP, Morning Surge)
        if (now.isAfter(MEAN_REVERSION_CUTOFF) && now.isBefore(MOMENTUM_CUTOFF)) {
            if (scanner.contains("VWAP") || scanner.contains("MORNING_SURGE")) {
                log.info("Chartink: TIME EXIT for mean reversion {} ({}) at {}",
                        pos.getSymbol(), scanner, now);
                executionService.closePosition(pos.getSymbol(), "TIME_EXIT_MR_2PM");
                return;
            }
        }

        // After 3:15 PM: close ALL remaining positions (EOD)
        if (now.isAfter(MOMENTUM_CUTOFF)) {
            log.info("Chartink: TIME EXIT EOD for {} ({}) at {}", pos.getSymbol(), scanner, now);
            executionService.closePosition(pos.getSymbol(), "TIME_EXIT_EOD_315PM");
        }
    }

    private String getScannerName(Long signalId) {
        if (signalId == null) return "";
        return signalRepository.findById(signalId)
                .map(s -> s.getScannerName() != null ? s.getScannerName() : "")
                .orElse("");
    }
}
