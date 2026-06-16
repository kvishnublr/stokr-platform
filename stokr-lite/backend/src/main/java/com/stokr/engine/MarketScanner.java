package com.stokr.engine;

import com.stokr.marketdata.MarketDataService;
import com.stokr.marketdata.Universe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketScanner {

    private final MarketDataService marketDataService;
    private final Universe universe;

    /**
     * Scan the universe for trading opportunities.
     * Returns list of symbols with available market data.
     */
    public java.util.List<String> getScannableSymbols() {
        if (!marketDataService.isMarketOpen()) {
            return java.util.Collections.emptyList();
        }
        // In production, could filter by volume, sector, etc.
        return universe.getSymbols();
    }

    public boolean hasValidData(String symbol) {
        BigDecimal ltp = marketDataService.getLtp(symbol);
        return ltp.compareTo(BigDecimal.ZERO) > 0;
    }
}
