package com.stokr.common.events;

import java.time.Instant;

/** Published after platform Zerodha OAuth completes successfully. */
public record PlatformZerodhaOAuthResolvedEvent(String vendor, Instant at) {
}
