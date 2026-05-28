package com.stokr.intraday.service;

import com.stokr.intraday.domain.NseStock;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.stream.RealTimeSetupStream;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps {@link RealTimeSetupStream} fed from live candle data + setup detectors during NSE session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdvIntelligenceFeedService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TF = "1m";

    private final MarketDataQueryService marketDataQueryService;
    private final SetupDetectionService setupDetectionService;
    private final RealTimeSetupStream realTimeStream;
    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;

    @Value("${stokr.adv-dashboard.universe-group:NIFTY_50}")
    private String universeGroupKey;

    @Value("${stokr.adv-dashboard.max-symbols:50}")
    private int maxSymbols;

    @Scheduled(fixedDelayString = "${stokr.adv-dashboard.feed-interval-ms:45000}")
    public void refreshFromMarketData() {
        if (!isNseSessionOpen()) {
            return;
        }
        refreshUniverse(false);
    }

    /** Manual refresh for API cold-start (runs even after NSE close using latest candles). */
    public void refreshNow() {
        refreshUniverse(true);
    }

    private void refreshUniverse(boolean allowAfterHours) {
        if (!allowAfterHours && !isNseSessionOpen()) {
            return;
        }
        try {
            List<String> symbols = resolveUniverseSymbols();
            if (symbols.isEmpty()) {
                log.debug("adv.feed.skip no_universe_symbols group={}", universeGroupKey);
                return;
            }

            Map<String, SetupDetectionService.MarketData> marketData = new LinkedHashMap<>();
            List<NseStock> stocks = new ArrayList<>();
            BigDecimal niftyPrice = null;

            for (String raw : symbols) {
                String sym = normalizeSymbol(raw);
                String querySymbol = sym.startsWith("NSE:") ? sym : "NSE:" + sym;
                List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(querySymbol, TF, 120);
                if (bars.isEmpty()) {
                    continue;
                }
                SetupDetectionService.MarketData md = toMarketData(bars);
                if (md == null) {
                    continue;
                }
                marketData.put(stripPrefix(sym), md);
                stocks.add(toStock(stripPrefix(sym), md));
                realTimeStream.processTick(
                        stripPrefix(sym),
                        md.currentPrice,
                        md.currentVwap,
                        md.openPrice,
                        md.volume,
                        md.atr14,
                        niftyPrice != null ? niftyPrice : md.currentPrice
                );
                if ("NIFTY 50".equalsIgnoreCase(stripPrefix(sym)) || "NIFTY50".equalsIgnoreCase(stripPrefix(sym))) {
                    niftyPrice = md.currentPrice;
                }
            }

            if (marketData.isEmpty()) {
                log.debug("adv.feed.skip no_candle_data symbols_tried={}", symbols.size());
                return;
            }

            MarketRegimeDetector.MarketRegime regime = realTimeStream.getCurrentRegime();
            List<com.stokr.intraday.domain.CurrentSetup> setups =
                    setupDetectionService.getTopSetups(stocks, marketData, regime, 23);
            realTimeStream.updateRankingBoard(setups);
            log.info("adv.feed.refreshed symbols={} setups={} ticks={}",
                    marketData.size(), setups.size(), realTimeStream.getStatistics().tickCount);
        } catch (Exception ex) {
            log.warn("adv.feed.error {}", ex.getMessage(), ex);
        }
    }

    private List<String> resolveUniverseSymbols() {
        Optional<StrategyUniverseGroup> group = groupRepository.findByGroupKey(universeGroupKey);
        if (group.isEmpty()) {
            group = groupRepository.findByGroupKey("NIFTY_100");
        }
        if (group.isEmpty()) {
            return List.of();
        }
        List<StrategyUniverseSymbol> rows = symbolRepository.findAllByGroupIdAndEnabledTrue(group.get().getId());
        return rows.stream()
                .map(StrategyUniverseSymbol::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .limit(maxSymbols)
                .toList();
    }

    private static SetupDetectionService.MarketData toMarketData(List<MarketdataCandle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        MarketdataCandle last = bars.get(bars.size() - 1);
        BigDecimal close = last.getClosePrice();
        if (close == null || close.signum() <= 0) {
            return null;
        }

        LocalDate today = LocalDate.now(IST);
        BigDecimal sessionOpen = null;
        long volumeSum = 0;
        BigDecimal vwapNum = BigDecimal.ZERO;
        BigDecimal vwapDen = BigDecimal.ZERO;

        for (MarketdataCandle bar : bars) {
            if (bar.getOpenTime() == null) {
                continue;
            }
            LocalDate barDay = bar.getOpenTime().atZone(IST).toLocalDate();
            if (!barDay.equals(today)) {
                continue;
            }
            if (sessionOpen == null && bar.getOpenPrice() != null) {
                sessionOpen = bar.getOpenPrice();
            }
            long vol = bar.getVolume() != null ? bar.getVolume().longValue() : 0L;
            volumeSum += vol;
            if (vol > 0 && bar.getClosePrice() != null) {
                BigDecimal v = BigDecimal.valueOf(vol);
                vwapNum = vwapNum.add(bar.getClosePrice().multiply(v));
                vwapDen = vwapDen.add(v);
            }
        }

        if (sessionOpen == null) {
            sessionOpen = bars.get(0).getOpenPrice();
        }
        BigDecimal vwap = vwapDen.signum() > 0
                ? vwapNum.divide(vwapDen, 4, RoundingMode.HALF_UP)
                : close;

        SetupDetectionService.MarketData md = new SetupDetectionService.MarketData(
                close, vwap, sessionOpen != null ? sessionOpen : close, volumeSum, estimateAtr(bars));
        return md;
    }

    private static BigDecimal estimateAtr(List<MarketdataCandle> bars) {
        int n = Math.min(14, bars.size());
        if (n < 2) {
            return BigDecimal.ONE;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = bars.size() - n; i < bars.size(); i++) {
            MarketdataCandle c = bars.get(i);
            if (c.getHighPrice() != null && c.getLowPrice() != null) {
                sum = sum.add(c.getHighPrice().subtract(c.getLowPrice()).abs());
            }
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP).max(BigDecimal.valueOf(0.01));
    }

    private static NseStock toStock(String symbol, SetupDetectionService.MarketData md) {
        return NseStock.builder()
                .stockId(symbol)
                .stockName(symbol)
                .sector("General")
                .averageVolumeDaily(Math.max(100_000L, md.volume != null ? md.volume : 100_000L))
                .prevClose(md.openPrice)
                .build();
    }

    private static boolean isNseSessionOpen() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 30));
    }

    private static String normalizeSymbol(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    private static String stripPrefix(String sym) {
        if (sym.contains(":")) {
            return sym.substring(sym.indexOf(':') + 1);
        }
        return sym;
    }
}
