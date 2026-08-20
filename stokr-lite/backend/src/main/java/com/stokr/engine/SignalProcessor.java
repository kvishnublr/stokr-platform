package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.marketdata.DailyCandleBuilder;
import com.stokr.marketdata.MarketDataService;
import com.stokr.marketdata.Universe;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import com.stokr.strategy.MarketContext;
import com.stokr.strategy.Signal;
import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessor {

    private final StrategyService strategyService;
    private final MarketDataService marketDataService;
    private final ZerodhaLiveDataScheduler liveData;
    private final EntryManager entryManager;
    private final SignalRepository signalRepository;
    private final Universe universe;
    private final DailyCandleBuilder dailyCandleBuilder;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * These strategies are written around multi-day-hold, daily-bar logic (e.g. 50-day EMA,
     * RSI(14) over trading days) -- they need real daily candles, not 1-minute intraday bars.
     *
     * VCP_BREAKOUT was missing from this set -- it evaluated on 1-minute intraday candles
     * instead of daily bars, so its "ATR(14)" and 50/150/200-EMA Trend Template were computed
     * over 14 one-MINUTE bars instead of 14 trading DAYS. That collapsed the stop-loss distance
     * (1.5x ATR) to a few paise (e.g. WIPRO SL only Rs0.04 from entry), triggering SL_HIT within
     * minutes on ordinary bid-ask noise, and the still-true breakout condition kept re-firing on
     * the same symbol every ~1-min scan cycle, showing up as repeated REJECTED signals from the
     * cooldown/min-gap risk rules. Caught via the Signals dashboard: SL/target bands a fraction
     * of a rupee wide on stocks trading in the hundreds, and a 22% win rate driven almost
     * entirely by SL_HIT within single-digit minutes of entry.
     */
    private static final java.util.Set<String> DAILY_CANDLE_STRATEGY_TYPES = java.util.Set.of(
        "OVERSOLD_BOUNCE", "EMA50_DISTANCE", "THREE_RED_DAYS", "RSI_OVERSOLD", "VCP_BREAKOUT");
    // VCP_BREAKOUT requires n>=210 daily bars (200-day EMA + Trend Template) and scans up to
    // n-250 for a 52-week high -- 90 was plenty for the other daily strategies but far short of
    // what VCP needs, so it would never generate a signal even once fed real daily candles.
    private static final int DAILY_LOOKBACK_DAYS = 260;

    public void processDeployment(Deployment deployment) {
        try {
            Strategy strategy = strategyService.getStrategy(deployment.getStrategyId());
            if (strategy == null || !strategy.isEnabled()) return;

            LocalDateTime istNow = LocalDateTime.now(IST);
            LocalDateTime todayOpen = LocalDate.now(IST).atTime(9, 15);
            List<String> symbols = universe.getSymbols();

            boolean dailyStrategy = DAILY_CANDLE_STRATEGY_TYPES.contains(strategy.getStrategyType());

            for (String symbol : symbols) {
                try {
                    List<Candle> candles = dailyStrategy
                        ? dailyCandleBuilder.build(symbol, DAILY_LOOKBACK_DAYS)
                        : marketDataService.getCandlesBetween(symbol, "1min", todayOpen, istNow);
                    if (candles.size() < 16) continue;

                    BigDecimal ltp = candles.get(candles.size() - 1).close();
                    if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

                    BigDecimal vwap = computeVwap(candles);
                    Map<String, BigDecimal> indicators = new HashMap<>();
                    Map<String, Object> extras = new HashMap<>();

                    MarketContext context = new MarketContext(symbol, candles, ltp, vwap, indicators, extras);
                    Signal signal = strategyService.evaluateSignal(deployment.getStrategyId(), context);
                    if (signal == null || !signal.isValid()) continue;

                    boolean alreadyOpen = signalRepository
                        .findFirstByDeploymentIdAndSymbolAndStatusInOrderByCreatedAtDesc(
                            deployment.getId(), symbol, List.of("EXECUTED", "GENERATED"))
                        .isPresent();
                    if (alreadyOpen) continue;

                    SignalEntity entity = SignalEntity.builder()
                        .deploymentId(deployment.getId())
                        .userId(deployment.getUserId())
                        .strategyId(deployment.getStrategyId())
                        .symbol(signal.symbol())
                        .side(SignalEntity.Side.valueOf(signal.side().name()))
                        .entryPrice(signal.entryPrice())
                        .stopLoss(signal.stopLoss())
                        .target(signal.target())
                        .confidence(BigDecimal.valueOf(signal.confidence()))
                        .reason(signal.reason())
                        .status("GENERATED")
                        .source(SignalEntity.SignalSource.INTERNAL)
                        .trailTriggerPct(signal.trailTriggerPct())
                        .trailDistancePct(signal.trailDistancePct())
                        .build();
                    signalRepository.save(entity);

                    boolean entered = entryManager.processEntrySignal(deployment, signal);
                    entity.setStatus(entered ? "EXECUTED" : "REJECTED");
                    signalRepository.save(entity);
                } catch (Exception e) {
                    log.error("Error processing symbol {} for deployment {}", symbol, deployment.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error in processDeployment {}", deployment.getId(), e);
        }
    }

    private BigDecimal computeVwap(List<Candle> candles) {
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (Candle c : candles) {
            BigDecimal typical = c.high().add(c.low()).add(c.close()).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
            BigDecimal volume = BigDecimal.valueOf(c.volume());
            pv = pv.add(typical.multiply(volume));
            vol = vol.add(volume);
        }
        if (vol.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return pv.divide(vol, 4, RoundingMode.HALF_UP);
    }

    private List<CandleData> toCandleDataList(List<Candle> candles) {
        List<CandleData> list = new ArrayList<>();
        for (Candle c : candles) {
            list.add(new CandleData(null, null, "1min", c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume(), null));
        }
        return list;
    }
}
