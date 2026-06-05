package io.stokr.oms.service;

import io.stokr.oms.domain.entity.OmsExecution;
import io.stokr.oms.domain.entity.OmsOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OmsExecutionSignalValidatorTest {

    private OmsExecutionSignalValidator validator;
    private UUID signalId;
    private UUID executionId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        validator = new OmsExecutionSignalValidator();
        signalId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        orderId = UUID.randomUUID();
    }

    @Test
    void testValidateSignalLinkage_LIVE_WithValidSignalId_Passes() {
        // Given
        OmsOrder order = OmsOrder.builder()
            .id(orderId)
            .executionMode("LIVE")
            .signalId(signalId)
            .build();

        OmsExecution execution = OmsExecution.builder()
            .id(executionId)
            .signalId(signalId)
            .isSynthetic(false)
            .build();

        // When & Then
        assertDoesNotThrow(() -> validator.validateSignalLinkage(execution, order));
    }

    @Test
    void testValidateSignalLinkage_LIVE_WithoutSignalId_Throws() {
        // Given
        OmsOrder order = OmsOrder.builder()
            .id(orderId)
            .executionMode("LIVE")
            .signalId(null)  // CRITICAL: Missing signal_id
            .build();

        OmsExecution execution = OmsExecution.builder()
            .id(executionId)
            .isSynthetic(false)
            .build();

        // When & Then
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> validator.validateSignalLinkage(execution, order)
        );
        assertTrue(exception.getMessage().contains("LIVE execution requires signal_id"));
    }

    @Test
    void testValidateSignalLinkage_PAPER_SignalIdOptional() {
        // Given
        OmsOrder order = OmsOrder.builder()
            .id(orderId)
            .executionMode("PAPER")
            .signalId(null)  // Optional in PAPER mode
            .build();

        OmsExecution execution = OmsExecution.builder()
            .id(executionId)
            .isSynthetic(false)
            .build();

        // When & Then
        assertDoesNotThrow(() -> validator.validatePaperModeSignalOptional(execution, order));
    }

    @Test
    void testValidateSignalLinkage_SIMULATION_SignalIdOptional() {
        // Given
        OmsOrder order = OmsOrder.builder()
            .id(orderId)
            .executionMode("SIMULATION")
            .signalId(null)  // Optional in SIMULATION mode
            .build();

        OmsExecution execution = OmsExecution.builder()
            .id(executionId)
            .isSynthetic(false)
            .build();

        // When & Then
        assertDoesNotThrow(() -> validator.validatePaperModeSignalOptional(execution, order));
    }

    @Test
    void testValidateSignalLinkage_SyntheticExecution_AllowsMismatch() {
        // Given - Synthetic execution may have different signal_id
        UUID brokerSignalId = UUID.randomUUID();
        OmsOrder order = OmsOrder.builder()
            .id(orderId)
            .executionMode("LIVE")
            .signalId(signalId)
            .build();

        OmsExecution execution = OmsExecution.builder()
            .id(executionId)
            .signalId(brokerSignalId)  // Different signal_id (from broker)
            .isSynthetic(true)  // But it's synthetic
            .build();

        // When & Then
        assertDoesNotThrow(() -> validator.validateSignalLinkage(execution, order));
    }

    @Test
    void testHasValidSignalLinkage_ReturnsTrueWhenPresent() {
        // Given
        OmsExecution execution = OmsExecution.builder()
            .signalId(signalId)
            .build();

        // When
        boolean result = validator.hasValidSignalLinkage(execution);

        // Then
        assertTrue(result);
    }

    @Test
    void testHasValidSignalLinkage_ReturnsFalseWhenMissing() {
        // Given
        OmsExecution execution = OmsExecution.builder()
            .signalId(null)
            .build();

        // When
        boolean result = validator.hasValidSignalLinkage(execution);

        // Then
        assertFalse(result);
    }

    @Test
    void testIsExecutionSynthetic_ReturnsTrueWhenMarked() {
        // Given
        OmsExecution execution = OmsExecution.builder()
            .isSynthetic(true)
            .build();

        // When
        boolean result = validator.isExecutionSynthetic(execution);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsExecutionSynthetic_ReturnsFalseWhenNotMarked() {
        // Given
        OmsExecution execution = OmsExecution.builder()
            .isSynthetic(false)
            .build();

        // When
        boolean result = validator.isExecutionSynthetic(execution);

        // Then
        assertFalse(result);
    }

    @Test
    void testNullInputs_ThrowsException() {
        // When & Then
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateSignalLinkage(null, new OmsOrder())
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateSignalLinkage(new OmsExecution(), null)
        );
    }

    @Test
    void testCriticalRule_LiveExecutionMustHaveSignalId() {
        // This test documents the CRITICAL RULE:
        // Every LIVE execution MUST have signal_id linkage
        // This prevents orphan orders in the system

        // Given - LIVE order without signal
        OmsOrder liveOrder = OmsOrder.builder()
            .executionMode("LIVE")
            .signalId(null)
            .build();

        OmsExecution orphanExecution = OmsExecution.builder()
            .isSynthetic(false)
            .build();

        // When & Then - Should ALWAYS throw
        assertThrows(
            IllegalStateException.class,
            () -> validator.validateSignalLinkage(orphanExecution, liveOrder),
            "LIVE execution without signal_id is a CRITICAL rule violation"
        );
    }
}
