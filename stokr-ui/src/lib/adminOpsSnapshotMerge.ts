import { hasPlatformMarketFeedConnected } from "../components/admin/adminReadinessModel";
import { asRecord, type OpsSnapshot } from "../components/admin/cockpit/opsTypes";

function platformVendorMapEmpty(s: OpsSnapshot | undefined): boolean {
  const root = asRecord(s?.platformMarketFeed);
  const vendors = asRecord(root?.vendors);
  return !vendors || Object.keys(vendors).length === 0;
}

/**
 * SSE `tick` payloads sometimes omit `platformMarketFeed` while HTTP snapshot + broker-infrastructure
 * include it. When the incoming tick has no vendor map but the cached snapshot had a connected
 * platform session, carry forward so readiness pills stay aligned until the next full fetch.
 */
export function carryForwardPlatformMarketFeed(next: OpsSnapshot, prev: OpsSnapshot | undefined): OpsSnapshot {
  if (!prev) return next;
  if (!platformVendorMapEmpty(next)) return next;
  if (!hasPlatformMarketFeedConnected(prev)) return next;
  const p = asRecord(prev.platformMarketFeed);
  const pv = asRecord(p?.vendors) ?? {};
  const nroot = asRecord(next.platformMarketFeed);
  return {
    ...next,
    platformMarketFeed: {
      ...(nroot ?? {}),
      ...p,
      vendors: { ...(asRecord(nroot?.vendors) ?? {}), ...pv },
    },
  };
}

/**
 * Ensures `platformMarketFeed.vendors` on the operations snapshot matches the dedicated
 * broker-infrastructure API (same backend source of truth as the Broker Infrastructure page).
 */
export function mergePlatformMarketFeedIntoSnapshot(snap: OpsSnapshot, infra: unknown): OpsSnapshot {
  const infraRec = asRecord(infra);
  const iv = asRecord(infraRec?.vendors);
  if (!iv || Object.keys(iv).length === 0) {
    return snap;
  }
  const existing = asRecord(snap.platformMarketFeed);
  const ev = asRecord(existing?.vendors) ?? {};
  const mergedVendors: Record<string, unknown> = { ...ev };
  for (const [k, val] of Object.entries(iv)) {
    mergedVendors[k] = val;
  }
  return {
    ...snap,
    platformMarketFeed: {
      ...(existing ?? {}),
      ...infraRec,
      vendors: mergedVendors,
    },
  };
}
