package com.stokr.execution.truth;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Position reconciliation report between broker and internal ledger.
 * Identifies gaps: external acquisitions, missing positions, quantity mismatches.
 */
@Data
@NoArgsConstructor
public class PositionReconciliationReport {

    private Instant timestamp;

    /**
     * Positions broker has but we don't track (external acquisition)
     */
    private List<PositionGap> externalPositions = new ArrayList<>();

    /**
     * Positions we track but broker doesn't have (external exit)
     */
    private List<PositionGap> missingPositions = new ArrayList<>();

    /**
     * Positions where qty doesn't match
     */
    private List<QuantityMismatch> qtyMismatches = new ArrayList<>();

    public void addExternalPosition(String symbol, BigDecimal qty, BigDecimal price) {
        externalPositions.add(new PositionGap(symbol, qty, price));
    }

    public void addMissingPosition(String symbol, BigDecimal qty, BigDecimal price) {
        missingPositions.add(new PositionGap(symbol, qty, price));
    }

    public void addQtyMismatch(String symbol, BigDecimal internalQty, BigDecimal brokerQty) {
        qtyMismatches.add(new QuantityMismatch(symbol, internalQty, brokerQty));
    }

    public boolean hasDiscrepancies() {
        return !externalPositions.isEmpty() || !missingPositions.isEmpty() || !qtyMismatches.isEmpty();
    }

    @Data
    public static class PositionGap {
        private final String symbol;
        private final BigDecimal quantity;
        private final BigDecimal price;

        public PositionGap(String symbol, BigDecimal quantity, BigDecimal price) {
            this.symbol = symbol;
            this.quantity = quantity;
            this.price = price;
        }
    }

    @Data
    public static class QuantityMismatch {
        private final String symbol;
        private final BigDecimal internalQty;
        private final BigDecimal brokerQty;

        public QuantityMismatch(String symbol, BigDecimal internalQty, BigDecimal brokerQty) {
            this.symbol = symbol;
            this.internalQty = internalQty;
            this.brokerQty = brokerQty;
        }
    }
}
