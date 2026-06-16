package com.stokr.execution.safety;

import com.stokr.common.market.MarketSegmentUtil;
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
    private LocalTime nseNoNewEntriesAfter;

    @Value("${stokr.oms.market-close.flatten-time:15:15}")
    private LocalTime nseFlattenTime;

    @Value("${stokr.oms.market-close.flatten-end:15:30}")
    private LocalTime nseFlattenEnd;

    @Value("${stokr.oms.market-close.mcx-no-new-entries-after:23:55}")
    private LocalTime mcxNoNewEntriesAfter;

    @Value("${stokr.oms.market-close.mcx-flatten-time:23:50}")
    private LocalTime mcxFlattenTime;

    @Value("${stokr.oms.market-close.mcx-flatten-end:23:55}")
    private LocalTime mcxFlattenEnd;

    @Value("${stokr.oms.market-close.force-close-stale-positions:true}")
    private boolean forceCloseStalePositions;

    public boolean blocksNewLiveEntries(Instant now) {
        return blocksNewLiveEntries(now, null, null);
    }

    public boolean blocksNewLiveEntries(Instant now, String symbol, String strategyKey) {
        if (!isMarketDay(now)) {
            return true;
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        LocalTime cutoff = isMcxContext(symbol, strategyKey) ? mcxNoNewEntriesAfter : nseNoNewEntriesAfter;
        return !t.isBefore(cutoff);
    }

    public boolean shouldFlatten(Instant now) {
        return shouldFlatten(now, null, null);
    }

    public boolean shouldFlatten(Instant now, String symbol, String strategyKey) {
        if (!isMarketDay(now)) {
            return false;
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        if (isMcxContext(symbol, strategyKey)) {
            return !t.isBefore(mcxFlattenTime) && t.isBefore(mcxFlattenEnd);
        }
        return !t.isBefore(nseFlattenTime) && t.isBefore(nseFlattenEnd);
    }

    public boolean forceCloseStalePositionsEnabled() {
        return forceCloseStalePositions;
    }

    public LocalTime noNewEntriesAfter() {
        return nseNoNewEntriesAfter;
    }

    public LocalTime flattenTime() {
        return nseFlattenTime;
    }

    public LocalTime mcxNoNewEntriesAfter() {
        return mcxNoNewEntriesAfter;
    }

    public LocalTime mcxFlattenTime() {
        return mcxFlattenTime;
    }

    public LocalTime mcxFlattenEnd() {
        return mcxFlattenEnd;
    }

    private boolean isMcxContext(String symbol, String strategyKey) {
        return MarketSegmentUtil.isMcxContext(symbol, strategyKey);
    }

    private boolean isMarketDay(Instant now) {
        var dow = now.atZone(zone).getDayOfWeek();
        return dow.getValue() < 6;
    }
}
