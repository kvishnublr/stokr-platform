package com.stokr.bootstrap.trader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DISABLED: TraderTerminalViewService depends on removed modules (stokr-execution, stokr-backtest).
 * This stub is kept to prevent compilation errors.
 * For NSE_SPIKE_DETECTION V2.0, use MarketdataCandle and OMS services directly.
 */
@Service
@Slf4j
public class TraderTerminalViewService {

    public TraderTerminalViewService() {
        log.warn("TraderTerminalViewService is disabled (stub only)");
    }
}
