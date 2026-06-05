package io.stokr.oms.service;

import io.stokr.oms.domain.entity.ManualExitSuppression;
import io.stokr.oms.domain.entity.PositionLifecycleAudit;
import io.stokr.oms.repository.ManualExitSuppressionRepository;
import io.stokr.oms.repository.PositionLifecycleAuditRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalBrokerExitHandlerTest {

    @Mock
    private ManualExitSuppressionRepository suppressionRepository;

    @Mock
    private PositionLifecycleAuditRepository auditRepository;

    @InjectMocks
    private ExternalBrokerExitHandler exitHandler;

    private UUID userId;
    private UUID positionId;
    private String symbol;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        symbol = "NIFTY";
    }

    @Test
    void testHandleManualBrokerExit_CreatesSuppressionRecord() {
        // Given
        BigDecimal exitQuantity = new BigDecimal("1");
        BigDecimal exitPrice = new BigDecimal("18000.50");

        when(suppressionRepository.save(any(ManualExitSuppression.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitHandler.handleManualBrokerExit(positionId, userId, symbol,
            "ZERODHA_APP", exitQuantity, exitPrice);

        // Then
        verify(suppressionRepository).save(argThat(suppression ->
            positionId.equals(suppression.getPositionId()) &&
            suppression.getSuppressAllExits() &&
            suppression.getSuppressSlExit() &&
            suppression.getSuppressTargetExit() &&
            "ZERODHA_APP".equals(suppression.getManualExitSource())
        ));
    }

    @Test
    void testHandleManualBrokerExit_CreatesAuditEntry() {
        // Given
        BigDecimal exitQuantity = new BigDecimal("1");
        BigDecimal exitPrice = new BigDecimal("18000.50");

        when(suppressionRepository.save(any(ManualExitSuppression.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(auditRepository.save(any(PositionLifecycleAudit.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitHandler.handleManualBrokerExit(positionId, userId, symbol,
            "KITE_WEB", exitQuantity, exitPrice);

        // Then
        verify(auditRepository).save(argThat(audit ->
            positionId.equals(audit.getPositionId()) &&
            "CLOSED".equals(audit.getNewState()) &&
            "MANUAL".equals(audit.getOwnerType()) &&
            "MANUAL_BROKER".equals(audit.getExitSource())
        ));
    }

    @Test
    void testIsManualExitSuppressed_ReturnsTrueWhenSuppressed() {
        // Given
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressAllExits(true)
            .build();

        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        // When
        boolean result = exitHandler.isManualExitSuppressed(positionId);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsManualExitSuppressed_ReturnsFalseWhenNotSuppressed() {
        // Given
        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.empty());

        // When
        boolean result = exitHandler.isManualExitSuppressed(positionId);

        // Then
        assertFalse(result);
    }

    @Test
    void testCanExecuteExit_StoplossExit_Blocked() {
        // Given
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressSlExit(true)
            .suppressTargetExit(false)
            .build();

        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        // When
        boolean canExit = exitHandler.canExecuteExit(positionId, "SL");

        // Then
        assertFalse(canExit);
    }

    @Test
    void testCanExecuteExit_TargetExit_Blocked() {
        // Given
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressSlExit(false)
            .suppressTargetExit(true)
            .build();

        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        // When
        boolean canExit = exitHandler.canExecuteExit(positionId, "TARGET");

        // Then
        assertFalse(canExit);
    }

    @Test
    void testCanExecuteExit_PressureExit_Blocked() {
        // Given
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressSlExit(false)
            .suppressTargetExit(false)
            .suppressPressureExit(true)
            .build();

        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        // When
        boolean canExit = exitHandler.canExecuteExit(positionId, "PRESSURE");

        // Then
        assertFalse(canExit);
    }

    @Test
    void testCanExecuteExit_AllExitsBlocked() {
        // Given
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressAllExits(true)
            .suppressSlExit(true)
            .suppressTargetExit(true)
            .suppressPressureExit(true)
            .suppressFeedProtectionExit(true)
            .suppressAutoExit(true)
            .build();

        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        // When & Then - All exit types should be blocked
        assertFalse(exitHandler.canExecuteExit(positionId, "SL"));
        assertFalse(exitHandler.canExecuteExit(positionId, "TARGET"));
        assertFalse(exitHandler.canExecuteExit(positionId, "PRESSURE"));
        assertFalse(exitHandler.canExecuteExit(positionId, "FEED"));
        assertFalse(exitHandler.canExecuteExit(positionId, "AUTO"));
    }

    @Test
    void testCanExecuteExit_NoSuppression_AllowsAllExits() {
        // Given
        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.empty());

        // When & Then - All exit types should be allowed
        assertTrue(exitHandler.canExecuteExit(positionId, "SL"));
        assertTrue(exitHandler.canExecuteExit(positionId, "TARGET"));
        assertTrue(exitHandler.canExecuteExit(positionId, "PRESSURE"));
        assertTrue(exitHandler.canExecuteExit(positionId, "FEED"));
        assertTrue(exitHandler.canExecuteExit(positionId, "AUTO"));
    }

    @Test
    void testRemoveSuppression_DeletesRecord() {
        // Given
        when(suppressionRepository.deleteByPositionId(positionId))
            .thenReturn(1);  // 1 record deleted

        // When
        exitHandler.removeSuppression(positionId);

        // Then
        verify(suppressionRepository).deleteByPositionId(positionId);
    }

    @Test
    void testManualExitFromZerodhaApp_SuppressesDuplicateExits() {
        // Given - User manually closes position in Zerodha app
        BigDecimal exitQty = new BigDecimal("1");
        BigDecimal exitPrice = new BigDecimal("18050");

        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .suppressAllExits(true)
            .suppressSlExit(true)
            .suppressTargetExit(true)
            .build();

        when(suppressionRepository.save(any(ManualExitSuppression.class)))
            .thenReturn(suppression);

        // When
        exitHandler.handleManualBrokerExit(positionId, userId, symbol,
            "ZERODHA_APP", exitQty, exitPrice);

        // Then - No subsequent exit should be allowed
        when(suppressionRepository.findByPositionId(positionId))
            .thenReturn(Optional.of(suppression));

        assertFalse(exitHandler.canExecuteExit(positionId, "SL"));
        assertFalse(exitHandler.canExecuteExit(positionId, "TARGET"));
    }
}
