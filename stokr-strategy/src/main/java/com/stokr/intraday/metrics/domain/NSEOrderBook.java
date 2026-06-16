package com.stokr.intraday.metrics.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal NSE order book representation extracted from Zerodha WebSocket ticks.
 * Since Zerodha sends only aggregated totalBuyQuantity/totalSellQuantity,
 * we synthesize bid/ask levels for the order flow confidence calculator.
 */
@Data
@AllArgsConstructor
public class NSEOrderBook {
    private String symbol;
    private BigDecimal lastPrice;
    private long totalBuyQuantity;
    private long totalSellQuantity;

    /**
     * Synthesize bid levels from aggregated buy quantity.
     * Distributes total buy quantity across 5 synthetic bid levels
     * slightly below the last price.
     */
    public List<OrderLevel> getBidLevels() {
        List<OrderLevel> bidLevels = new ArrayList<>();
        if (lastPrice != null && totalBuyQuantity > 0) {
            // Create 5 synthetic bid levels below price
            long qtyPerLevel = totalBuyQuantity / 5;
            BigDecimal tickSize = BigDecimal.valueOf(0.05);

            for (int i = 1; i <= 5; i++) {
                BigDecimal bidPrice = lastPrice.subtract(tickSize.multiply(BigDecimal.valueOf(i)));
                long qty = (i == 5) ? (totalBuyQuantity - (qtyPerLevel * 4)) : qtyPerLevel;
                bidLevels.add(new OrderLevel(bidPrice, qty));
            }
        }
        return bidLevels;
    }

    /**
     * Synthesize ask levels from aggregated sell quantity.
     * Distributes total sell quantity across 5 synthetic ask levels
     * slightly above the last price.
     */
    public List<OrderLevel> getAskLevels() {
        List<OrderLevel> askLevels = new ArrayList<>();
        if (lastPrice != null && totalSellQuantity > 0) {
            // Create 5 synthetic ask levels above price
            long qtyPerLevel = totalSellQuantity / 5;
            BigDecimal tickSize = BigDecimal.valueOf(0.05);

            for (int i = 1; i <= 5; i++) {
                BigDecimal askPrice = lastPrice.add(tickSize.multiply(BigDecimal.valueOf(i)));
                long qty = (i == 5) ? (totalSellQuantity - (qtyPerLevel * 4)) : qtyPerLevel;
                askLevels.add(new OrderLevel(askPrice, qty));
            }
        }
        return askLevels;
    }
}
