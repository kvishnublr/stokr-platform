package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFlowValidationService {

    private final OrderFlowSnapshotRepository snapshotRepository;

    // Cache historical close prices (symbol -> price)
    private final Map<String, BigDecimal> historicalCloseCache = new ConcurrentHashMap<>();

    // Cache validation thresholds per symbol
    private final Map<String, ValidationThresholds> thresholdsCache = new ConcurrentHashMap<>();

    /**
     * Comprehensive validation of order flow snapshot
     * Returns validation result with detailed reasons if invalid
     */
    public ValidationResult validateSnapshot(OrderFlowSnapshot snapshot) {
        ValidationResult result = new ValidationResult();
        result.setSymbol(snapshot.getSymbol());
        result.setSnapshotId(snapshot.getId());

        // Check 1: Timestamp is recent (< 2 seconds old)
        if (!isTimestampFresh(snapshot)) {
            result.addError("STALE_TIMESTAMP",
                "Snapshot is > 2 seconds old");
            return result;  // Early return for stale data
        }

        // Check 2: Bid < Ask (basic order book integrity)
        if (!isBidAskValid(snapshot)) {
            result.addError("INVALID_BID_ASK",
                "Bid price (" + snapshot.getBidPrice() + ") >= Ask price (" + snapshot.getAskPrice() + ")");
            return result;
        }

        // Check 3: Spread is reasonable (< 1% of price)
        if (!isSpreadReasonable(snapshot)) {
            result.addError("EXCESSIVE_SPREAD",
                "Spread is " + snapshot.getSpreadPct() + "% (limit: 1.0%)");
            return result;
        }

        // Check 4: Volumes are non-zero and reasonable
        if (!areVolumesValid(snapshot)) {
            result.addError("INVALID_VOLUMES",
                "Bid volume: " + snapshot.getBidVolume() +
                ", Ask volume: " + snapshot.getAskVolume());
            return result;
        }

        // Check 5: Mid price hasn't moved excessively vs historical
        if (!isMidPriceReasonable(snapshot)) {
            result.addError("EXCESSIVE_PRICE_MOVE",
                "Price movement > 20% from expected range");
            return result;
        }

        // Check 6: Ratios are sensible
        if (!ratiosAreSensible(snapshot)) {
            result.addError("INVALID_RATIOS",
                "Bid/Ask ratio or imbalance is unrealistic");
            return result;
        }

        // Check 7: Liquidity score is in valid range
        if (snapshot.getLiquidityScore() != null &&
            (snapshot.getLiquidityScore() < 0 || snapshot.getLiquidityScore() > 100)) {
            result.addError("INVALID_LIQUIDITY_SCORE",
                "Liquidity score out of range: " + snapshot.getLiquidityScore());
            return result;
        }

        // Check 8: Pressure scores are in valid range
        if ((snapshot.getBuyerPressureScore() != null &&
             (snapshot.getBuyerPressureScore() < 0 || snapshot.getBuyerPressureScore() > 100)) ||
            (snapshot.getSellerPressureScore() != null &&
             (snapshot.getSellerPressureScore() < 0 || snapshot.getSellerPressureScore() > 100))) {
            result.addError("INVALID_PRESSURE_SCORES",
                "Pressure scores out of range");
            return result;
        }

        // All checks passed
        result.setValid(true);
        snapshot.setIsValid(true);
        log.debug("validation.passed symbol={} snapshot_id={}",
            snapshot.getSymbol(), snapshot.getId());

        return result;
    }

    /**
     * Check if timestamp is recent (< 2 seconds old)
     */
    private boolean isTimestampFresh(OrderFlowSnapshot snapshot) {
        if (snapshot.getTimestamp() == null) {
            return false;
        }
        Instant twoSecondsAgo = Instant.now().minus(2, ChronoUnit.SECONDS);
        return snapshot.getTimestamp().isAfter(twoSecondsAgo);
    }

    /**
     * Check if bid < ask (fundamental requirement)
     */
    private boolean isBidAskValid(OrderFlowSnapshot snapshot) {
        if (snapshot.getBidPrice() == null || snapshot.getAskPrice() == null) {
            return false;
        }
        return snapshot.getBidPrice().compareTo(snapshot.getAskPrice()) < 0;
    }

    /**
     * Check if spread is reasonable (< 1% of mid price)
     * NSE normal spread is 0.01-0.05% for liquid stocks
     */
    private boolean isSpreadReasonable(OrderFlowSnapshot snapshot) {
        if (snapshot.getSpreadPct() == null) {
            return false;
        }
        // Allow up to 1% spread (covers even low-liquidity cases)
        return snapshot.getSpreadPct().compareTo(BigDecimal.valueOf(1.0)) < 0;
    }

    /**
     * Check if volumes are non-zero and sensible
     */
    private boolean areVolumesValid(OrderFlowSnapshot snapshot) {
        // Both sides must have some volume
        if (snapshot.getBidVolume() == null || snapshot.getAskVolume() == null) {
            return false;
        }

        // Both must be > 0
        if (snapshot.getBidVolume() <= 0 || snapshot.getAskVolume() <= 0) {
            return false;
        }

        // Volumes shouldn't be unreasonably large (> 100M shares at single level)
        // This prevents garbage data
        if (snapshot.getBidVolume() > 100_000_000L || snapshot.getAskVolume() > 100_000_000L) {
            return false;
        }

        return true;
    }

    /**
     * Check if mid price is reasonable relative to last known close
     * Allows up to 20% move in extreme cases
     */
    private boolean isMidPriceReasonable(OrderFlowSnapshot snapshot) {
        BigDecimal midPrice = snapshot.getMidPrice();
        if (midPrice == null || midPrice.signum() <= 0) {
            return false;
        }

        // Get historical close price
        BigDecimal prevClose = getHistoricalClose(snapshot.getSymbol());
        if (prevClose == null) {
            // No historical data, can't validate - assume valid
            return true;
        }

        // Calculate percentage change
        BigDecimal change = midPrice.subtract(prevClose).abs()
            .divide(prevClose, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));

        // Allow up to 20% move (covers gap-up/gap-down scenarios)
        return change.compareTo(BigDecimal.valueOf(20)) <= 0;
    }

    /**
     * Check if ratios and imbalance are sensible
     */
    private boolean ratiosAreSensible(OrderFlowSnapshot snapshot) {
        // Bid/Ask ratio should be between 0.2 and 5.0
        // (not expecting more than 5:1 imbalance in either direction)
        if (snapshot.getBidAskRatio() != null) {
            BigDecimal ratio = snapshot.getBidAskRatio();
            if (ratio.compareTo(BigDecimal.valueOf(0.2)) < 0 ||
                ratio.compareTo(BigDecimal.valueOf(5.0)) > 0) {
                return false;
            }
        }

        // Imbalance should not exceed total volume
        if (snapshot.getImbalance() != null &&
            snapshot.getBidVolume() != null &&
            snapshot.getAskVolume() != null) {
            long maxImbalance = snapshot.getBidVolume() + snapshot.getAskVolume();
            if (Math.abs(snapshot.getImbalance()) > maxImbalance) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get historical close price for validation
     * Cached for performance
     */
    private BigDecimal getHistoricalClose(String symbol) {
        if (historicalCloseCache.containsKey(symbol)) {
            return historicalCloseCache.get(symbol);
        }

        // In production, would fetch from market data service
        // For now, return null to skip validation
        return null;
    }

    /**
     * Update historical close price cache
     */
    public void updateHistoricalClose(String symbol, BigDecimal closePrice) {
        historicalCloseCache.put(symbol, closePrice);
    }

    /**
     * Validation result object
     */
    public static class ValidationResult {
        private String symbol;
        private java.util.UUID snapshotId;
        private boolean valid = true;
        private final Map<String, String> errors = new HashMap<>();

        public void addError(String code, String message) {
            this.valid = false;
            this.errors.put(code, message);
        }

        public boolean isValid() {
            return valid;
        }

        public Map<String, String> getErrors() {
            return errors;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public void setSnapshotId(java.util.UUID snapshotId) {
            this.snapshotId = snapshotId;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult{symbol='" + symbol + "', valid=true}";
            }
            return "ValidationResult{symbol='" + symbol + "', errors=" + errors + '}';
        }
    }

    /**
     * Validation thresholds (can be adjusted per symbol)
     */
    public static class ValidationThresholds {
        public BigDecimal maxSpreadPct = BigDecimal.valueOf(1.0);
        public BigDecimal maxPriceChangePercent = BigDecimal.valueOf(20);
        public long maxVolumePerLevel = 100_000_000L;
        public double minBidAskRatio = 0.2;
        public double maxBidAskRatio = 5.0;
    }
}
