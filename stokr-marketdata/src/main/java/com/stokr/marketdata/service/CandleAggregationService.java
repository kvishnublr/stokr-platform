package com.stokr.marketdata.service;

import com.stokr.marketdata.runtime.InMemoryCandleManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CandleAggregationService {

    private final InMemoryCandleManager candleManager;

    public void onTick(String symbol, Instant ts, BigDecimal lastPrice) {
        candleManager.appendClose(symbol, ts, lastPrice.setScale(8, RoundingMode.HALF_UP));
    }
}
