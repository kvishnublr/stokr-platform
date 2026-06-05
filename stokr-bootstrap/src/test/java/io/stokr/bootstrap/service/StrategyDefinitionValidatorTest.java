package io.stokr.bootstrap.service;

import io.stokr.bootstrap.domain.entity.StrategyDefinition;
import io.stokr.bootstrap.repository.StrategyDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyDefinitionValidatorTest {

    @Mock
    private StrategyDefinitionRepository definitionRepository;

    @InjectMocks
    private StrategyDefinitionValidator validator;

    private UUID userId;
    private String strategyName;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        strategyName = "TREND_FOLLOWER_1";
    }

    @Test
    void testIsStrategyFullyDocumented_MissingDefinition_ReturnsFalse() {
        // Given - No definition found
        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.empty());

        // When
        boolean result = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsStrategyFullyDocumented_MissingEntryCriteria_ReturnsFalse() {
        // Given - Definition without entry criteria
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId(null)  // Missing
            .entryCriteriaDescription(null)  // Missing
            .exitTriggerId("TARGET")
            .exitCriteriaDescription("Exit at profit target")
            .hasTargetExit(true)
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));

        // When
        boolean result = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsStrategyFullyDocumented_MissingExitCriteria_ReturnsFalse() {
        // Given - Definition without exit criteria
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId("SIGNAL")
            .entryCriteriaDescription("Buy on bullish signal")
            .exitTriggerId(null)  // Missing
            .exitCriteriaDescription(null)  // Missing
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));

        // When
        boolean result = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsStrategyFullyDocumented_NoExitPathDocumented_ReturnsFalse() {
        // Given - No exit paths (no target, SL, trailing, time, or pressure exit)
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId("SIGNAL")
            .entryCriteriaDescription("Buy on signal")
            .exitTriggerId("TARGET")
            .exitCriteriaDescription("Exit at target")
            .hasTargetExit(false)
            .hasStoplossExit(false)
            .hasTrailingExit(false)
            .hasTimeExit(false)
            .hasPressureExit(false)
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));

        // When
        boolean result = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsStrategyFullyDocumented_AllCriteriaPresent_ReturnsTrue() {
        // Given - Complete definition
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId("SIGNAL")
            .entryCriteriaDescription("Buy on bullish signal")
            .exitTriggerId("TARGET")
            .exitCriteriaDescription("Exit at 2% profit")
            .hasTargetExit(true)
            .targetExitRule("exit if price >= entry + 2%")
            .hasStoplossExit(true)
            .stoplossExitRule("exit if price <= entry - 1%")
            .isDocumented(true)
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));
        when(definitionRepository.save(any(StrategyDefinition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        boolean result = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then
        assertTrue(result);
        verify(definitionRepository).save(argThat(d -> d.getIsDocumented()));
    }

    @Test
    void testValidateForCompliance_ApprovedWhenAllFieldsFilled() {
        // Given - Complete and valid definition
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId("SIGNAL")
            .entryCriteriaDescription("Entry rule")
            .exitTriggerId("TARGET")
            .exitCriteriaDescription("Exit rule")
            .hasTargetExit(true)
            .maxPositionSize(new BigDecimal("100000"))
            .maxDailyLoss(new BigDecimal("50000"))
            .riskPerTradePercent(new BigDecimal("2"))
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));
        when(definitionRepository.save(any(StrategyDefinition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        boolean result = validator.validateForCompliance(userId, strategyName);

        // Then
        assertTrue(result);
        verify(definitionRepository).save(argThat(d ->
            "APPROVED".equals(d.getComplianceReviewStatus())
        ));
    }

    @Test
    void testValidateForCompliance_FlaggedWhenMissingFields() {
        // Given - Definition with missing required fields
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .entryTriggerId(null)  // Missing
            .exitTriggerId("TARGET")
            .exitCriteriaDescription("Exit rule")
            .hasTargetExit(true)
            .maxPositionSize(null)  // Missing
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));
        when(definitionRepository.save(any(StrategyDefinition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        boolean result = validator.validateForCompliance(userId, strategyName);

        // Then
        assertFalse(result);
        verify(definitionRepository).save(argThat(d ->
            "FLAGGED".equals(d.getComplianceReviewStatus())
        ));
    }

    @Test
    void testFlagForComplianceReview_UpdatesStatus() {
        // Given
        StrategyDefinition def = StrategyDefinition.builder()
            .userId(userId)
            .strategyName(strategyName)
            .complianceReviewStatus("PENDING")
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));
        when(definitionRepository.save(any(StrategyDefinition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        validator.flagForComplianceReview(userId, strategyName, "Missing exit criteria");

        // Then
        verify(definitionRepository).save(argThat(d ->
            "FLAGGED".equals(d.getComplianceReviewStatus())
        ));
    }

    @Test
    void testGetComplianceViolations_MissingDefinition() {
        // Given - No definition exists
        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.empty());

        // When
        var violations = validator.getComplianceViolations(userId, strategyName);

        // Then
        assertTrue(violations.contains("Strategy definition missing"));
    }

    @Test
    void testGetComplianceViolations_NotDocumented() {
        // Given - Definition not marked as documented
        StrategyDefinition def = StrategyDefinition.builder()
            .isDocumented(false)
            .isValidated(true)
            .complianceReviewStatus("PENDING")
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));

        // When
        var violations = validator.getComplianceViolations(userId, strategyName);

        // Then
        assertTrue(violations.contains("Strategy not documented"));
    }

    @Test
    void testGetComplianceViolations_FlaggedByCompliance() {
        // Given - Definition flagged for review
        StrategyDefinition def = StrategyDefinition.builder()
            .isDocumented(true)
            .isValidated(true)
            .complianceReviewStatus("FLAGGED")
            .build();

        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(def));

        // When
        var violations = validator.getComplianceViolations(userId, strategyName);

        // Then
        assertTrue(violations.contains("Flagged for review"));
    }

    @Test
    void testCriticalRule_AllStrategiesMustBeDefined() {
        // This test documents the CRITICAL RULE from WS12:
        // EVERY strategy must have:
        // 1. Entry trigger + entry criteria description
        // 2. Exit trigger + exit criteria description
        // 3. At least one exit path (target, SL, trailing, time, or pressure)
        // 4. Risk parameters (max position, max daily loss, risk per trade)

        // Given - An undefined strategy
        when(definitionRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.empty());

        // When
        boolean isDocumented = validator.isStrategyFullyDocumented(userId, strategyName);

        // Then - Should fail validation
        assertFalse(isDocumented);
    }
}
