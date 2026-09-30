package com.stokr.smartstrategy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PopModelTest {

    @Test
    void yearsToExpiryCountsToCloseOnExpiryDay() {
        ZonedDateTime mondayNoon = ZonedDateTime.parse("2026-09-28T12:00:00+05:30[Asia/Kolkata]");
        double y = PopModel.yearsToExpiry(LocalDate.parse("2026-09-28"), mondayNoon);
        assertEquals(210.0 / (365 * 24 * 60), y, 1e-12); // 12:00 → 15:30
        double afterClose = PopModel.yearsToExpiry(LocalDate.parse("2026-09-28"),
            ZonedDateTime.parse("2026-09-28T16:00:00+05:30[Asia/Kolkata]"));
        assertEquals(1.0 / (365 * 24 * 60), afterClose, 1e-12, "floored at one minute");
    }

    @Test
    void wideRangeIsLikelyNarrowRangeIsNot() {
        double years = 7 / 365.0, iv = 0.14, spot = 24000;
        double wide = PopModel.popPct(spot, 23000, 25000, years, iv);
        double narrow = PopModel.popPct(spot, 23950, 24050, years, iv);
        assertTrue(wide > 90, "±4% over a week at 14% IV: " + wide);
        assertTrue(narrow < 25, "±0.2% over a week: " + narrow);
    }

    @Test
    void unboundedSidesAndInvalidRanges() {
        double years = 7 / 365.0, iv = 0.14, spot = 24000;
        double above = PopModel.popPct(spot, 24000, Double.NaN, years, iv);
        double below = PopModel.popPct(spot, Double.NaN, 24000, years, iv);
        assertEquals(100, above + below, 0.2);
        assertEquals(100, PopModel.popPct(spot, Double.NaN, Double.NaN, years, iv));
        assertEquals(0, PopModel.popPct(spot, 24100, 24000, years, iv));
    }
}
