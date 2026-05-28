package com.stokr.execution.sizing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Slf4j
public class BrokerNormalizationService {

    private static final BigDecimal DEFAULT_LOT_SIZE = BigDecimal.ONE;

    public record NormalizationResult(
            BigDecimal normalizedQuantity,
            boolean accepted,
            String note) {}

    public NormalizationResult normalize(String symbol, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return new NormalizationResult(BigDecimal.ZERO, false, "QUANTITY_ZERO");
        }
        BigDecimal lot = resolveLotSize(symbol);
        if (lot.compareTo(BigDecimal.ONE) <= 0) {
            BigDecimal rounded = quantity.setScale(0, RoundingMode.FLOOR);
            if (rounded.compareTo(BigDecimal.ZERO) <= 0) {
                return new NormalizationResult(BigDecimal.ZERO, false, "MIN_QTY");
            }
            return new NormalizationResult(rounded, true, null);
        }
        BigDecimal lots = quantity.divide(lot, 0, RoundingMode.FLOOR);
        if (lots.compareTo(BigDecimal.ZERO) <= 0) {
            return new NormalizationResult(BigDecimal.ZERO, false, "BELOW_LOT_SIZE:" + lot);
        }
        BigDecimal normalized = lots.multiply(lot);
        String note = normalized.compareTo(quantity) != 0 ? "LOT_ROUNDED:" + lot : null;
        return new NormalizationResult(normalized, true, note);
    }

    private BigDecimal resolveLotSize(String symbol) {
        if (symbol == null) {
            return DEFAULT_LOT_SIZE;
        }
        String s = symbol.toUpperCase();
        if (s.contains("BANKNIFTY") || s.contains("NIFTY_FUT") || s.contains("NIFTY-FUT")) {
            return new BigDecimal("15");
        }
        if (s.contains("FUT")) {
            return new BigDecimal("1");
        }
        return DEFAULT_LOT_SIZE;
    }
}
