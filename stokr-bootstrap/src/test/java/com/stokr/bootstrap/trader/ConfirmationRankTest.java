package com.stokr.bootstrap.trader;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationRankTest {

    @Test
    void strongSetupScoresAPlus() {
        ConfirmationRank.Result r = ConfirmationRank.rank(
                new BigDecimal("0.82"),
                new BigDecimal("2.0"),
                75);
        assertTrue(r.score() >= ConfirmationRank.SCORE_A_PLUS);
        assertEquals("A_PLUS", r.tier());
        assertTrue(r.highConviction());
    }

    @Test
    void lowRiskRewardCapsTier() {
        ConfirmationRank.Result r = ConfirmationRank.rank(
                new BigDecimal("0.90"),
                new BigDecimal("1.1"),
                null);
        assertTrue(r.score() < ConfirmationRank.SCORE_A_PLUS || "WATCH".equals(r.tier()));
    }
}
