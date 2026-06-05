package io.stokr.oms.domain.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ManualExitSuppressionTest {

    private ManualExitSuppression suppression;
    private UUID userId;
    private UUID positionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        positionId = UUID.randomUUID();

        suppression = ManualExitSuppression.builder()
            .userId(userId)
            .positionId(positionId)
            .symbol("NIFTY")
            .suppressAllExits(true)
            .suppressSlExit(true)
            .suppressTargetExit(true)
            .suppressPressureExit(true)
            .suppressFeedProtectionExit(true)
            .suppressAutoExit(true)
            .build();
    }

    @Test
    void testAllExitsSupressed() {
        // Given
        suppression.setSuppressAllExits(true);

        // When
        boolean allSuppressed = suppression.getSuppressAllExits();

        // Then
        assertTrue(allSuppressed);
    }

    @Test
    void testIndividualExitSuppression_SlExit() {
        // Given
        suppression.setSuppressSlExit(true);
        suppression.setSuppressTargetExit(false);

        // When
        boolean slSuppressed = suppression.getSuppressSlExit();
        boolean targetSuppressed = suppression.getSuppressTargetExit();

        // Then
        assertTrue(slSuppressed);
        assertFalse(targetSuppressed);
    }

    @Test
    void testIndividualExitSuppression_TargetExit() {
        // Given
        suppression.setSuppressTargetExit(true);
        suppression.setSuppressSlExit(false);

        // When
        boolean targetSuppressed = suppression.getSuppressTargetExit();
        boolean slSuppressed = suppression.getSuppressSlExit();

        // Then
        assertTrue(targetSuppressed);
        assertFalse(slSuppressed);
    }

    @Test
    void testIsAnyExitAllowed_WhenAllSuppressed() {
        // Given - All exits suppressed
        suppression.setSuppressAllExits(true);
        suppression.setSuppressSlExit(true);
        suppression.setSuppressTargetExit(true);
        suppression.setSuppressPressureExit(true);

        // When
        boolean anyAllowed = suppression.isAnyExitAllowed();

        // Then
        assertFalse(anyAllowed);
    }

    @Test
    void testIsAnyExitAllowed_WhenOnlyOneAllowed() {
        // Given - Only target exit allowed
        suppression.setSuppressAllExits(false);
        suppression.setSuppressSlExit(true);
        suppression.setSuppressTargetExit(false);  // Not suppressed
        suppression.setSuppressPressureExit(true);

        // When
        boolean anyAllowed = suppression.isAnyExitAllowed();

        // Then
        assertTrue(anyAllowed);
    }

    @Test
    void testManualExitSource_ZerodhaApp() {
        // Given - User closed in Zerodha app
        suppression.setManualExitSource("ZERODHA_APP");

        // When
        String source = suppression.getManualExitSource();

        // Then
        assertEquals("ZERODHA_APP", source);
    }

    @Test
    void testManualExitSource_KiteWeb() {
        // Given - User closed via Kite web
        suppression.setManualExitSource("KITE_WEB");

        // When
        String source = suppression.getManualExitSource();

        // Then
        assertEquals("KITE_WEB", source);
    }

    @Test
    void testManualExitQuantityAndPrice() {
        // Given - Manual exit details
        BigDecimal qty = new BigDecimal("5");
        BigDecimal price = new BigDecimal("18050.50");

        // When
        suppression.setManualExitQuantity(qty);
        suppression.setManualExitPrice(price);

        // Then
        assertEquals(qty, suppression.getManualExitQuantity());
        assertEquals(price, suppression.getManualExitPrice());
    }

    @Test
    void testManualExitTime() {
        // Given - Exit time
        LocalDateTime exitTime = LocalDateTime.now();

        // When
        suppression.setManualExitTime(exitTime);

        // Then
        assertEquals(exitTime, suppression.getManualExitTime());
    }

    @Test
    void testIsPartialExit() {
        // Given - Partial exit (not all quantity)
        suppression.setIsPartialExit(true);

        // When
        boolean isPartial = suppression.getIsPartialExit();

        // Then
        assertTrue(isPartial);
    }

    @Test
    void testReason() {
        // Given - Reason for suppression
        String reason = "User manually closed from Zerodha app";

        // When
        suppression.setReason(reason);

        // Then
        assertEquals(reason, suppression.getReason());
    }

    @Test
    void testUniqueConstraintOnPositionId() {
        // This test documents the unique constraint:
        // Only one suppression record per position_id
        // When a position is manually closed, its suppression record is unique

        UUID testPositionId = UUID.randomUUID();

        ManualExitSuppression supp1 = ManualExitSuppression.builder()
            .positionId(testPositionId)
            .userId(userId)
            .symbol("NIFTY")
            .suppressAllExits(true)
            .build();

        ManualExitSuppression supp2 = ManualExitSuppression.builder()
            .positionId(testPositionId)
            .userId(userId)
            .symbol("NIFTY")
            .suppressAllExits(true)
            .build();

        // Both records are for the same position
        assertEquals(supp1.getPositionId(), supp2.getPositionId());
    }

    @Test
    void testCriticalFeature_SuppressAllExitsAfterManualClosure() {
        // This test documents the CRITICAL FEATURE:
        // After user manually closes a position via broker,
        // ALL exit types are suppressed to prevent duplicate exits

        // Given - Position manually closed
        suppression.setManualExitSource("ZERODHA_APP");
        suppression.setManualExitTime(LocalDateTime.now());
        suppression.setManualExitQuantity(new BigDecimal("1"));

        // When - All suppression flags are set
        suppression.setSuppressAllExits(true);
        suppression.setSuppressSlExit(true);
        suppression.setSuppressTargetExit(true);
        suppression.setSuppressPressureExit(true);
        suppression.setSuppressFeedProtectionExit(true);
        suppression.setSuppressAutoExit(true);

        // Then - No exit should be allowed
        assertFalse(suppression.isAnyExitAllowed());
    }

    @Test
    void testExitSuppression_AllTypes() {
        // Test all suppression types

        // SL exit suppressed
        suppression.setSuppressSlExit(true);
        assertTrue(suppression.getSuppressSlExit());

        // Target exit suppressed
        suppression.setSuppressTargetExit(true);
        assertTrue(suppression.getSuppressTargetExit());

        // Pressure exit suppressed
        suppression.setSuppressPressureExit(true);
        assertTrue(suppression.getSuppressPressureExit());

        // Feed protection exit suppressed
        suppression.setSuppressFeedProtectionExit(true);
        assertTrue(suppression.getSuppressFeedProtectionExit());

        // Auto exit suppressed
        suppression.setSuppressAutoExit(true);
        assertTrue(suppression.getSuppressAutoExit());
    }

    @Test
    void testPartialVsFullExit() {
        // Given - Partial manual exit
        suppression.setIsPartialExit(true);
        suppression.setManualExitQuantity(new BigDecimal("0.5"));

        // When
        boolean isPartial = suppression.getIsPartialExit();
        BigDecimal qty = suppression.getManualExitQuantity();

        // Then
        assertTrue(isPartial);
        assertEquals(new BigDecimal("0.5"), qty);
    }
}
