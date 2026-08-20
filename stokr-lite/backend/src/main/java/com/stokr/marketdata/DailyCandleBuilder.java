package com.stokr.marketdata;

import com.stokr.delivery.NseDeliveryData;
import com.stokr.delivery.NseDeliveryDataRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds genuine daily-bar candle history for strategies designed around multi-day-hold,
 * daily-timeframe logic (e.g. EMA50/RSI(14) over trading days, not minutes). Real historical
 * days come from NseDeliveryData (NSE's EOD bhavcopy); today's still-forming bar is synthesized
 * from the existing intraday 1-minute candles so the strategy has an accurate "today" reading
 * at any point in the session, not only after close.
 */
@Component
public class DailyCandleBuilder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NseDeliveryDataRepository deliveryRepo;
    private final MarketDataService marketDataService;

    public DailyCandleBuilder(NseDeliveryDataRepository deliveryRepo, MarketDataService marketDataService) {
        this.deliveryRepo = deliveryRepo;
        this.marketDataService = marketDataService;
    }

    public List<Candle> build(String symbol, int lookbackDays) {
        List<NseDeliveryData> history = deliveryRepo.findBySymbolOrderByDateDesc(symbol);

        List<Candle> daily = history.stream()
            .filter(d -> d.getOpenPrice() != null && d.getHighPrice() != null
                && d.getLowPrice() != null && d.getClosePrice() != null)
            .limit(lookbackDays)
            .sorted(Comparator.comparing(NseDeliveryData::getTradeDate))
            .map(d -> new Candle(symbol, d.getTradeDate().atTime(15, 30),
                d.getOpenPrice(), d.getHighPrice(), d.getLowPrice(), d.getClosePrice(),
                d.getTotalQty() != null ? d.getTotalQty() : 0))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Candle todayBar = buildTodaySyntheticBar(symbol);
        if (todayBar != null) daily.add(todayBar);

        return daily;
    }

    private Candle buildTodaySyntheticBar(String symbol) {
        LocalDateTime todayOpen = LocalDate.now(IST).atTime(9, 15);
        LocalDateTime now = LocalDateTime.now(IST);
        List<Candle> minuteCandles = marketDataService.getCandlesBetween(symbol, "1min", todayOpen, now);
        if (minuteCandles.isEmpty()) return null;

        BigDecimal open = minuteCandles.get(0).open();
        BigDecimal close = minuteCandles.get(minuteCandles.size() - 1).close();
        BigDecimal high = minuteCandles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(open);
        BigDecimal low = minuteCandles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(open);
        long volume = minuteCandles.stream().mapToLong(Candle::volume).sum();

        return new Candle(symbol, now, open, high, low, close, volume);
    }
}
