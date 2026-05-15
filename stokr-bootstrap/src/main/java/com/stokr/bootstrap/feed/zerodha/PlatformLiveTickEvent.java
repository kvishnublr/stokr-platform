package com.stokr.bootstrap.feed.zerodha;

import java.math.BigDecimal;
import java.time.Instant;

public record PlatformLiveTickEvent(String symbol, Instant tickTime, BigDecimal price, int instrumentToken) {
}
