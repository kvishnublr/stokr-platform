package com.stokr.strategy.catalog;

import java.util.List;

/**
 * Abstraction for syncing symbol membership of auto-managed universe groups.
 * Implementations can load from CSV, static config, or (in future) an NSE/MCX API.
 * Only groups with auto_managed=true are targeted by sync.
 */
public interface UniverseSyncService {

    /**
     * Syncs symbols for the given group key.
     * Returns the count of symbols upserted.
     */
    int sync(String groupKey);

    /**
     * Returns the group keys this service is capable of syncing.
     */
    List<String> supportedGroupKeys();
}
