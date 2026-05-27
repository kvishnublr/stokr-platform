package com.stokr.execution.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@Slf4j
public class MarketCloseProtectionService {

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.oms.market-close.no-new-entries-after:15:00}")
    private LocalTime noNewEntriesAfter;

    @Value("${stokr.oms.market-close.flatten-time:15:20}")
    private LocalTime flattenTime;

    @Value("${stokr.oms.market-close.force-close-stale-positions:true}")
    private boolean forceCloseStalePositions;

    public boolean blocksNewLiveEntries(Instant now) {
        if (!isMarketDay(now)) {
            return true;
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        return !t.isBefore(noNewEntriesAfter);
    }

    public boolean shouldFlatten(Instant now) {
        if (!isMarketDay(now)) {
            return false;
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        return !t.isBefore(flattenTime) && t.isBefore(LocalTime.of(15, 30));
    }

    public boolean forceCloseStalePositionsEnabled() {
        return forceCloseStalePositions;
    }

    public LocalTime noNewEntriesAfter() {
        return noNewEntriesAfter;
    }

    public LocalTime flattenTime() {
        return flattenTime;
    }

    private boolean isMarketDay(Instant now) {
        var dow = now.atZone(zone).getDayOfWeek();
        return dow.getValue() < 6;
    }
}
