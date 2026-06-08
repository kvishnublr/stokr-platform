package com.stokr.common.events;

import java.time.Instant;

/** Published when platform Zerodha OAuth reconnect is required (missing/expired refresh token). */
public record PlatformZerodhaOAuthRequiredEvent(String vendor, String reason, Instant at) {
}
