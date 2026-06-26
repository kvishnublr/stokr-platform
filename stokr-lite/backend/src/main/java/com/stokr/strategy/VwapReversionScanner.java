package com.stokr.strategy;

import com.stokr.chartink.ChartinkExecutionService;
import com.stokr.chartink.SignalCooldownService;
import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import com.stokr.engine.IndicatorUtils;
import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.marketdata.Candle;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VwapReversionScanner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MIN_CANDLES = 20;
    private static final int SCAN_INTERVAL_MINUTES = 1;

    private final CandleDataRepository candleDataRepository;
    private final SignalRepository signalRepository;
    private final StrategyService strategyService;
    private final SignalCooldownService cooldownService;
    private final ChartinkExecutionService executionService;

    private final Set<String> recentlyScanned = new HashSet<>();

    @Scheduled(cron = "45 */1 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanSymbols() {
        LocalDateTime now = LocalDateTime.now(IST);

        int totalHour = now.getHour() * 60 + now.getMinute();
        if (totalHour < 9 * 60 + 46 || totalHour > 14 * 60 + 30) return;

        if (!recentlyScanned.isEmpty()) {
            recentlyScanned.clear();
        }

        String strategyType = "VWAP_REVERSION";
        Strategy strategy;
        try {
            strategy = strategyService.getStrategyByType(strategyType);
        } catch (Exception e) {
            log.warn("VWAP_REVERSION strategy not found in DB — skipping scan");
            return;
        }
        if (!strategy.isEnabled()) return;

        StrategyPlugin plugin = strategyService.getPlugin(strategyType);
        StrategyParams params = StrategyParams.defaults();

        List<String> symbols = ZerodhaLiveDataScheduler.NIFTY_500;
        LocalDateTime from = now.minusHours(2);

        int signalsGenerated = 0;

        for (String symbol : symbols) {
            if (recentlyScanned.contains(symbol)) continue;

            List<CandleData> rawCandles = candleDataRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                            symbol, "1min", from, now);

            if (rawCandles.size() < MIN_CANDLES) continue;

            List<Candle> candles = rawCandles.stream()
                    .map(cd -> new Candle(cd.getSymbol(), cd.getTimestamp(),
                            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()))
                    .toList();

            List<IndicatorUtils.Indicators> indicators = IndicatorUtils.computeAll(rawCandles);
            int lastIdx = candles.size() - 1;
            Candle latest = candles.get(lastIdx);
            CandleData latestRaw = rawCandles.get(lastIdx);

            BigDecimal[] dayVwap = computePerDayVwap(rawCandles);
            BigDecimal vwap = dayVwap[lastIdx];
            if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) continue;

            Map<String, BigDecimal> indMap = new HashMap<>();
            IndicatorUtils.Indicators ind = indicators.get(lastIdx);
            if (ind.rsi14() != null) indMap.put("RSI14", ind.rsi14());
            if (ind.atr14() != null) indMap.put("ATR14", ind.atr14());
            if (ind.volSma10() != null) indMap.put("VOL_SMA_10", ind.volSma10());

            Map<String, Object> extras = new HashMap<>();
            extras.put("istHour", now.getHour());
            extras.put("istMinute", now.getMinute());
            extras.put("rsi14", ind.rsi14());
            extras.put("atr14", ind.atr14());
            extras.put("vwap", vwap);

            MarketContext context = new MarketContext(symbol, candles, latest.close(), vwap, indMap, extras);
            Signal signal = plugin.evaluate(context, params);

            if (signal != null && signal.isValid()) {
                if (!cooldownService.isAllowed(strategy.getId(), symbol, signal.side().name())) {
                    log.debug("VWAP Reversion: cooldown active for {} {}", symbol, signal.side());
                    continue;
                }
                cooldownService.record(strategy.getId(), symbol, signal.side().name());

                SignalEntity entity = SignalEntity.builder()
                        .strategyId(strategy.getId())
                        .symbol(symbol)
                        .side(signal.side() == Signal.Side.BUY ? SignalEntity.Side.BUY : SignalEntity.Side.SELL)
                        .entryPrice(signal.entryPrice())
                        .stopLoss(signal.stopLoss())
                        .target(signal.target())
                        .confidence(BigDecimal.valueOf(signal.confidence()))
                        .reason(signal.reason())
                        .status("GENERATED")
                        .source(SignalEntity.SignalSource.INTERNAL)
                        .scannerName("VWAP_REVERSION")
                        .trailTriggerPct(signal.trailTriggerPct())
                        .trailDistancePct(signal.trailDistancePct())
                        .build();

                entity = signalRepository.save(entity);

                try {
                    executionService.execute(entity);
                    signalsGenerated++;
                    log.info("VWAP Reversion: signal {} for {} {} (confidence={})",
                            entity.getId(), symbol, signal.side(), String.format("%.0f%%", signal.confidence() * 100));
                } catch (Exception e) {
                    log.error("VWAP Reversion: execution failed for {} {}: {}", symbol, signal.side(), e.getMessage());
                }

                recentlyScanned.add(symbol);
            }
        }

        if (signalsGenerated > 0) {
            log.info("VWAP Reversion scan complete: {} signals generated", signalsGenerated);
        }
    }

    private BigDecimal[] computePerDayVwap(List<CandleData> candles) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        String curDay = null;
        BigDecimal cumTpv = BigDecimal.ZERO;
        long cumVol = 0;
        for (int i = 0; i < n; i++) {
            String d = candles.get(i).getTimestamp().atZone(IST).toLocalDate().toString();
            if (!d.equals(curDay)) {
                curDay = d;
                cumTpv = BigDecimal.ZERO;
                cumVol = 0;
            }
            CandleData c = candles.get(i);
            BigDecimal tp = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), 4, java.math.RoundingMode.HALF_UP);
            cumTpv = cumTpv.add(tp.multiply(BigDecimal.valueOf(c.getVolume())));
            cumVol += c.getVolume();
            result[i] = cumVol > 0
                    ? cumTpv.divide(BigDecimal.valueOf(cumVol), 4, java.math.RoundingMode.HALF_UP)
                    : c.getClose();
        }
        return result;
    }
}
