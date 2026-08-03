package com.stokr.arbitrage;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * Legacy WS tick-cycle auto-trader.
 * Disabled: live bid-parity execution is owned by {@link OptionArbAutoExecService}
 * (3-leg CE+PE+FUT, DB-backed settings). Kept as a bean so existing injections compile.
 */
@Service
public class BidParityAutoTrader {

    public boolean isRunning() {
        return false;
    }

    public String getStatus() {
        return "DISABLED_USE_OPTION_ARB_AUTO_EXEC";
    }

    public Map<String, Object> getAllLiveTicks() {
        return Collections.emptyMap();
    }
}
