package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.2 Relative Volume (RVOL) Surge
 * Volume is the fuel that drives price.
 */
public class VolumeFilter {

    public enum Decision { SKIP, CAUTION, ENTER, ENTER_AGGRESSIVE }

    public static Decision evaluateRvol(BigDecimal rvol) {
        if (rvol == null) return Decision.SKIP;
        double r = rvol.doubleValue();
        if (r < 0.7) return Decision.SKIP;
        if (r < 1.2) return Decision.CAUTION;
        if (r < 2.0) return Decision.ENTER;
        return Decision.ENTER_AGGRESSIVE;
    }

    public static double score(BigDecimal rvol) {
        if (rvol == null) return 0;
        double r = rvol.doubleValue();
        if (r >= 2.0) return 100;
        if (r >= 1.2) return 80;
        if (r >= 0.7) return 40;
        return 0;
    }
}
