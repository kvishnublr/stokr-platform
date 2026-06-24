package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.marketdata.MarketDataService;
import com.stokr.marketdata.Universe;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates all enabled strategies against real-time DB candles every scan cycle.
 *
 * Per-symbol logic:
 *   1. Load today's 1-min candles from DB (populated by ZerodhaLiveDataScheduler)
 *   2. Compute ORB levels from first 15 candles (9:15-9:29), prefer cached snapshot at 9:30
 *   3. Compute intraday VWAP from candle data
 *   4. Build MarketContext with all extras and run enabled strategy plugins
 *   5. Valid signals are persisted and forwarded to EntryManager
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessor {

    private final StrategyService            strategyService;
    private final MarketDataService          marketDataService;
    private final ZerodhaLiveDataScheduler   liveData;
    private final EntryManager               entryManager;
    private final SignalRepository           signalRepository;
    private final Universe                   universe;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public void processDeployment(Deployment deployment) {
        try {
            Strategy strategy = strategyService.getStrategy(deployment.getStrategyId());
            if (!strategy.isEnabled()) return;

            LocalDateTime istNow    = LocalDateTime.now(IST);
            LocalDateTime todayOpen = LocalDate.now(IST).atTime(9, 15);

            List<String> symbols = universe.getSymbols();
            int signalCount = 0;

            for (String symbol : symbols) {
                try {
                    // Today's 1-min candles from DB
                    List<Candle> candles = marketDataService.getCandlesBetween(
                        symbol, "1min", todayOpen, istNow);

                    if (candles.size() < 16) continue; // Need ORB (15) + at least 1 breakout candle

                    BigDecimal ltp = marketDataService.getLtp(symbol);
                    if (ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // ORB: first 15 candles define the opening range
                    List<Candle> orbCandles = candles.subList(0, 15);
                    BigDecimal orbHigh = orbCandles.stream().map(Candle::high)
                        .max(BigDecimal::compareTo).orElse(null);
                    BigDecimal orbLow = orbCandles.stream().map(Candle::low)
                        .min(BigDecimal::compareTo).orElse(null);

                    // Prefer ORB snapshot cached at exactly 9:30 from Zerodha day high/low
                    BigDecimal cachedHigh = liveData.getOrbHigh(symbol);
                    BigDecimal cachedLow  = liveData.getOrbLow(symbol);
                    if (cachedHigh != null && cachedHigh.compareTo(BigDecimal.ZERO) > 0) orbHigh = cachedHigh;
                    if (cachedLow  != null && cachedLow.compareTo(BigDecimal.ZERO)  > 0) orbLow  = cachedLow;

                    if (orbHigh == null || orbLow == null) continue;
                    BigDecimal orbRange = orbHigh.subtract(orbLow);

                    // Intraday VWAP from candles
                    BigDecimal vwap = computeVwap(candles);

                    Map<String, BigDecimal> indicators = new HashMap<>();
                    if (candles.size() >= 20) {
                        List<CandleData> cdList = toCandleDataList(candles);
                        IndicatorUtils.Indicators ind = IndicatorUtils.computeAll(cdList).get(candles.size() - 1);
                        if (ind.rsi14() != null) indicators.put("RSI14", ind.rsi14());
                        if (ind.atr14() != null) indicators.put("ATR14", ind.atr14());
                        if (ind.volSma10() != null) indicators.put("VOL_SMA_10", ind.volSma10());
                    }

                    Map<String, Object> extras = new HashMap<>();
                    extras.put("orbHigh",   orbHigh);
                    extras.put("orbLow",    orbLow);
                    extras.put("orbRange",  orbRange);
                    extras.put("dayOpen",   candles.get(0).open());
                    extras.put("istHour",   istNow.getHour());
                    extras.put("istMinute", istNow.getMinute());
                    extras.put("chartinkOk", Boolean.TRUE); // all symbols pass; Chartink filter optional
                    extras.put("vwap",      vwap);
                    if (indicators.containsKey("RSI14")) extras.put("rsi14", indicators.get("RSI14"));
                    if (indicators.containsKey("ATR14")) extras.put("atr14", indicators.get("ATR14"));

                    MarketContext context = new MarketContext(
                        symbol, candles, ltp, vwap, indicators, extras);

                    Signal signal = strategyService.evaluateSignal(deployment.getStrategyId(), context);
                    if (signal == null || !signal.isValid()) continue;

                    // Deduplicate: skip if we already have an EXECUTED signal for this symbol today
                    boolean alreadyOpen = signalRepository
                        .findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                            deployment.getId(), symbol, "EXECUTED")
                        .isPresent();
                    if (alreadyOpen) continue;

                    log.info("Signal: {} {} entry={} sl={} tgt={} reason={}",
                        deployment.getId(), symbol,
                        signal.entryPrice(), signal.stopLoss(), signal.target(), signal.reason());

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
                        .build();
                    signalRepository.save(entity);

                    entryManager.processEntrySignal(deployment, signal);
                    signalCount++;

                } catch (Exception e) {
                    log.error("Error processing symbol {} for deployment {}", symbol, deployment.getId(), e);
                }
            }

            if (signalCount > 0) {
                log.info("Scan complete for deployment {}: {} signals generated", deployment.getId(), signalCount);
            }

        } catch (Exception e) {
            log.error("Error in processDeployment {}", deployment.getId(), e);
        }
    }

    private BigDecimal computeVwap(List<Candle> candles) {
        BigDecimal totalVolume   = BigDecimal.ZERO;
        BigDecimal totalTpVolume = BigDecimal.ZERO; // typical_price × volume

        for (Candle c : candles) {
            BigDecimal vol = BigDecimal.valueOf(c.volume());
            if (vol.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal tp = c.high().add(c.low()).add(c.close())
                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            totalTpVolume = totalTpVolume.add(tp.multiply(vol));
            totalVolume   = totalVolume.add(vol);
        }

        if (totalVolume.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalTpVolume.divide(totalVolume, 2, RoundingMode.HALF_UP);
    }

    private List<CandleData> toCandleDataList(List<Candle> candles) {
        return candles.stream().map(c -> {
            CandleData cd = new CandleData();
            cd.setHigh(c.high());
            cd.setLow(c.low());
            cd.setOpen(c.open());
            cd.setClose(c.close());
            cd.setVolume(c.volume());
            cd.setSymbol(c.symbol());
            return cd;
        }).collect(Collectors.toList());
    }
}
