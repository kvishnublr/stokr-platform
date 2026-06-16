package com.stokr.common.events;

import java.util.UUID;

public record SignalPublishedEvent(UUID signalId, UUID userId, String symbol, String strategyKey) {
}
