package com.stokr.marketdata;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface MarketDataService {

    List<Candle> getCandles(String symbol, String interval, int count);

    BigDecimal getLtp(String symbol);

    List<Candle> getCandlesBetween(String symbol, String interval, LocalDateTime from, LocalDateTime to);

    boolean isMarketOpen();
}
