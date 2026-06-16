package com.stokr.execution.validation;

import com.stokr.oms.domain.OmsOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pre-flight validation before sending orders to broker.
 * Prevents broker rejections by validating funds, margin, and position limits upfront.
 *
 * TODO: Implement actual broker account info fetching from broker API
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreFlightValidationService {

    // Configuration (can be externalized)
    private static final BigDecimal MIN_MARGIN_BUFFER = new BigDecimal("0.20"); // 20% buffer

    /**
     * Validate that an order can be executed on the broker.
     * Returns validation result with pass/fail and reason.
     *
     * NOTE: Currently returns success - actual broker account info integration coming soon
     */
    public PreFlightValidationResult validate(OmsOrder order, UUID userId) {
        if (order == null || userId == null) {
            return PreFlightValidationResult.failure("ORDER_VALIDATION_FAILED", "Invalid order or user");
        }

        try {
            // TODO: Fetch actual broker account info from broker API
            // For now, returning success - full implementation will:
            // 1. Get available margin from broker
            // 2. Get open positions count
            // 3. Validate symbol tradeable
            // 4. Check position limits
            // 5. Apply margin buffer

            log.info("pre_flight_validation.pending_broker_integration orderId={} userId={} symbol={}",
                    order.getId(), userId, order.getSymbol());

            return PreFlightValidationResult.success(
                    "VALIDATION_PASSED",
                    "Pre-flight validation deferred to broker (awaiting account API integration)"
            );

        } catch (Exception ex) {
            log.error("pre_flight_validation.exception orderId={} userId={}", order.getId(), userId, ex);
            return PreFlightValidationResult.failure(
                    "VALIDATION_ERROR",
                    "Validation failed: " + ex.getMessage()
            );
        }
    }

    /**
     * Result of pre-flight validation.
     */
    public static class PreFlightValidationResult {
        public enum Status { PASS, WARNING, FAIL }

        private final Status status;
        private final String code;
        private final String message;

        private PreFlightValidationResult(Status status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public static PreFlightValidationResult success(String code, String message) {
            return new PreFlightValidationResult(Status.PASS, code, message);
        }

        public static PreFlightValidationResult warning(String code, String message) {
            return new PreFlightValidationResult(Status.WARNING, code, message);
        }

        public static PreFlightValidationResult failure(String code, String message) {
            return new PreFlightValidationResult(Status.FAIL, code, message);
        }

        public boolean passed() { return status == Status.PASS; }
        public boolean isFail() { return status == Status.FAIL; }
        public boolean isWarning() { return status == Status.WARNING; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
        public Status getStatus() { return status; }
    }
}