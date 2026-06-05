package io.stokr.bootstrap.service;

import io.stokr.bootstrap.domain.entity.StrategyDefinition;
import io.stokr.bootstrap.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyDefinitionValidator {

    private final StrategyDefinitionRepository definitionRepository;

    @Transactional(readOnly = true)
    public boolean isStrategyFullyDocumented(UUID userId, String strategyName) {
        var definition = definitionRepository.findByUserIdAndStrategyName(userId, strategyName);

        if (definition.isEmpty()) {
            log.error("COMPLIANCE_VIOLATION: Strategy {} has NO definition for user {}", strategyName, userId);
            return false;
        }

        var def = definition.get();

        // REQUIRED: Both entry and exit criteria
        if (def.getEntryTriggerId() == null || def.getEntryCriteriaDescription() == null) {
            log.error("COMPLIANCE_VIOLATION: {} - Entry criteria missing", strategyName);
            return false;
        }

        if (def.getExitTriggerId() == null || def.getExitCriteriaDescription() == null) {
            log.error("COMPLIANCE_VIOLATION: {} - Exit criteria missing", strategyName);
            return false;
        }

        // REQUIRED: At least one exit path documented
        if (!hasAtLeastOneExitPath(def)) {
            log.error("COMPLIANCE_VIOLATION: {} - No exit paths documented", strategyName);
            return false;
        }

        // Mark as documented
        def.setIsDocumented(true);
        definitionRepository.save(def);

        log.info("Strategy {} fully documented for user {}", strategyName, userId);
        return true;
    }

    @Transactional
    public boolean validateForCompliance(UUID userId, String strategyName) {
        var definition = definitionRepository.findByUserIdAndStrategyName(userId, strategyName);
        if (definition.isEmpty()) {
            return false;
        }

        var def = definition.get();
        List<String> violations = new ArrayList<>();

        // Check all required fields
        if (def.getEntryTriggerId() == null) violations.add("Entry trigger missing");
        if (def.getEntryCriteriaDescription() == null) violations.add("Entry criteria description missing");
        if (def.getExitTriggerId() == null) violations.add("Exit trigger missing");
        if (def.getExitCriteriaDescription() == null) violations.add("Exit criteria description missing");

        // Check exit paths
        if (!hasAtLeastOneExitPath(def)) violations.add("No exit paths documented");

        // Check risk parameters
        if (def.getMaxPositionSize() == null) violations.add("Max position size not defined");
        if (def.getMaxDailyLoss() == null) violations.add("Max daily loss not defined");
        if (def.getRiskPerTradePercent() == null) violations.add("Risk per trade not defined");

        if (!violations.isEmpty()) {
            log.error("Compliance violations for {}: {}", strategyName, violations);
            def.setComplianceReviewStatus("FLAGGED");
        } else {
            def.setComplianceReviewStatus("APPROVED");
            log.info("Strategy {} approved for compliance", strategyName);
        }

        definitionRepository.save(def);
        return violations.isEmpty();
    }

    @Transactional
    public void flagForComplianceReview(UUID userId, String strategyName, String reason) {
        var definition = definitionRepository.findByUserIdAndStrategyName(userId, strategyName);
        if (definition.isPresent()) {
            var def = definition.get();
            def.setComplianceReviewStatus("FLAGGED");
            definitionRepository.save(def);
            log.warn("Strategy {} flagged for compliance review: {}", strategyName, reason);
        }
    }

    private boolean hasAtLeastOneExitPath(StrategyDefinition def) {
        return (def.getHasTargetExit() != null && def.getHasTargetExit()) ||
               (def.getHasStoplossExit() != null && def.getHasStoplossExit()) ||
               (def.getHasTrailingExit() != null && def.getHasTrailingExit()) ||
               (def.getHasTimeExit() != null && def.getHasTimeExit()) ||
               (def.getHasPressureExit() != null && def.getHasPressureExit());
    }

    public List<StrategyDefinition> findMissingDefinitions(UUID userId) {
        // TODO: Query all strategies for user
        // Compare against strategy_definition table
        // Return those without definitions
        return List.of();
    }

    public List<String> getComplianceViolations(UUID userId, String strategyName) {
        var definition = definitionRepository.findByUserIdAndStrategyName(userId, strategyName);
        List<String> violations = new ArrayList<>();

        if (definition.isEmpty()) {
            violations.add("Strategy definition missing");
            return violations;
        }

        var def = definition.get();
        if (!def.getIsDocumented()) violations.add("Strategy not documented");
        if (!def.getIsValidated()) violations.add("Strategy not validated");
        if ("FLAGGED".equals(def.getComplianceReviewStatus())) violations.add("Flagged for review");
        if ("REJECTED".equals(def.getComplianceReviewStatus())) violations.add("Rejected by compliance");

        return violations;
    }
}
