package com.stokr.strategy.spec;

import com.stokr.strategy.domain.StrategyDefinition;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for public catalog queries (enabled + visible strategies).
 */
public final class StrategyDefinitionSpecifications {

    private StrategyDefinitionSpecifications() {
    }

    public static Specification<StrategyDefinition> catalogBase() {
        return (root, q, cb) -> cb.and(
                cb.isFalse(root.get("deleted")),
                cb.isTrue(root.get("enabled")),
                cb.isTrue(root.get("visibleToUsers"))
        );
    }

    public static Specification<StrategyDefinition> categoryIgnoreCase(String category) {
        if (category == null || category.isBlank()) {
            return (root, q, cb) -> cb.conjunction();
        }
        String trimmed = category.trim();
        return (root, q, cb) -> cb.equal(cb.lower(root.get("category")), trimmed.toLowerCase());
    }

    public static Specification<StrategyDefinition> riskLevelIgnoreCase(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return (root, q, cb) -> cb.conjunction();
        }
        String trimmed = riskLevel.trim();
        return (root, q, cb) -> cb.equal(cb.lower(root.get("riskLevel")), trimmed.toLowerCase());
    }
}
