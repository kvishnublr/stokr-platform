package com.stokr.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CashEntryLevelsTest {

    @Test
    void entriesPastTheSignalLevelsAreSkipped() {
        // 30 Sep 2026 trades: ESDS stop above entry; AUGMONT / BSE stops 0.7% / 0.5% under entry.
        assertNotNull(CashExecutionService.entryLevelsProblem(1509.50, 1882.53, 1533.29));
        assertNotNull(CashExecutionService.entryLevelsProblem(1032.05, 1193.08, 1025.02));
        assertNotNull(CashExecutionService.entryLevelsProblem(3102.70, 3530.73, 3088.00));
        assertNotNull(CashExecutionService.entryLevelsProblem(500, 480, 450), "already above target");
        // STAR: stop 3.3% below entry is a normal setup.
        assertNull(CashExecutionService.entryLevelsProblem(1170.10, 1283.80, 1131.56));
        assertNull(CashExecutionService.entryLevelsProblem(100, 0, 0), "no levels given");
    }
}
