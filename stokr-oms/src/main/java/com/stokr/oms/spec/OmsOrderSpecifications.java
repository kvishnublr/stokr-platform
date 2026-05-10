package com.stokr.oms.spec;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.query.PipelineMode;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class OmsOrderSpecifications {

    private OmsOrderSpecifications() {
    }

    public static Specification<OmsOrder> notDeleted() {
        return (root, q, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<OmsOrder> userId(UUID userId) {
        if (userId == null) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (root, q, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<OmsOrder> symbolEquals(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return (r, q, cb) -> cb.conjunction();
        }
        String s = symbol.trim();
        return (root, q, cb) -> cb.equal(root.get("symbol"), s);
    }

    public static Specification<OmsOrder> strategyKeyEquals(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (root, q, cb) -> cb.equal(root.get("strategyKey"), strategyKey.trim());
    }

    public static Specification<OmsOrder> brokerVendorEquals(String brokerVendor) {
        if (brokerVendor == null || brokerVendor.isBlank()) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (root, q, cb) -> cb.equal(root.get("brokerVendor"), brokerVendor.trim());
    }

    public static Specification<OmsOrder> stateEquals(OrderState state) {
        if (state == null) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (root, q, cb) -> cb.equal(root.get("state"), state);
    }

    public static Specification<OmsOrder> executionModeEquals(ExecutionMode mode) {
        if (mode == null) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (root, q, cb) -> cb.equal(root.get("executionMode"), mode);
    }

    public static Specification<OmsOrder> createdBetween(Instant from, Instant toExclusive) {
        return (root, q, cb) -> {
            var p = cb.conjunction();
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (toExclusive != null) {
                p = cb.and(p, cb.lessThan(root.get("createdAt"), toExclusive));
            }
            return p;
        };
    }

    public static Specification<OmsOrder> pipelineMode(PipelineMode mode) {
        if (mode == null || mode == PipelineMode.ALL) {
            return (r, q, cb) -> cb.conjunction();
        }
        if (mode == PipelineMode.LIVE) {
            return (root, q, cb) -> cb.isNull(root.get("backtestRunId"));
        }
        return (root, q, cb) -> cb.isNotNull(root.get("backtestRunId"));
    }
}
