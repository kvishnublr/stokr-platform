package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Blocks signal emission when the symbol's last price exceeds a configured ceiling (e.g. NIFTY 100 large-caps cap).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalSymbolPriceGateService {

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.max-entry-price:5000}")
    private BigDecimal maxEntryPrice;

    public boolean exceedsMaxPrice(StrategySignalEntity signal, Instant asOf) {
        if (maxEntryPrice == null || maxEntryPrice.signum() <= 0) {
            return false;
        }
        BigDecimal price = resolveReferencePrice(signal, asOf);
        if (price == null || price.signum() <= 0) {
            return false;
        }
        boolean over = price.compareTo(maxEntryPrice) > 0;
        if (over) {
            log.info("signal.dropped_price_ceiling symbol={} price={} max={} strategy={}",
                    signal.getSymbol(), price, maxEntryPrice, signal.getStrategyName());
        }
        return over;
    }

    private BigDecimal resolveReferencePrice(StrategySignalEntity signal, Instant asOf) {
        if (signal.getEntryReferencePrice() != null && signal.getEntryReferencePrice().signum() > 0) {
            return signal.getEntryReferencePrice();
        }
        String symbol = signal.getSymbol();
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        Instant ref = asOf != null ? asOf : Instant.now();
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, "1m", 3, ref);
        if (bars.isEmpty()) {
            bars = marketDataQueryService.lastBarsAscEndingAt(symbol, "5m", 3, ref);
        }
        if (bars.isEmpty()) {
            return null;
        }
        MarketdataCandle last = bars.get(bars.size() - 1);
        return last.getClosePrice();
    }
}
