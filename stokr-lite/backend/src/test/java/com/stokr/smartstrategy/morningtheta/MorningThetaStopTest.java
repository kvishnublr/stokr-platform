package com.stokr.smartstrategy.morningtheta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MorningThetaStopTest {
    @Test
    void stopFiresWhenLossEqualsPremiumCollected() {
        // Premium Rs 1,500 on a structure that can lose Rs 6,000: SL at 25% of max loss = Rs 1,500.
        assertEquals(25.0, MorningRangeThetaExecutor.slPctForPremiumStop(1500, 6000), 1e-9);
        assertEquals(100.0, MorningRangeThetaExecutor.slPctForPremiumStop(8000, 6000), 1e-9);
        assertEquals(100.0, MorningRangeThetaExecutor.slPctForPremiumStop(0, 6000), 1e-9);
    }
}
