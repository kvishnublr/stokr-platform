import { api } from "../api/client";
import type { OpsSnapshot } from "../components/admin/cockpit/opsTypes";
import { writeAdminOpsSnapshotCache } from "./adminOpsSnapshotCache";
import { mergePlatformMarketFeedIntoSnapshot } from "./adminOpsSnapshotMerge";

/** Snapshot can be slow on cold cache; server returns stale cache when warmed. */
const SNAPSHOT_TIMEOUT_MS = 25_000;
const INFRA_TIMEOUT_MS = 5_000;

async function fetchSnapshotCore(): Promise<OpsSnapshot> {
  const snapRes = await api.get("/api/admin/operations/snapshot", { timeout: SNAPSHOT_TIMEOUT_MS });
  return snapRes.data?.data as OpsSnapshot;
}

/**
 * Fast path for readiness strip: snapshot first (persisted to session cache), infra merged when available.
 */
export async function fetchAdminOpsSnapshotMerged(): Promise<OpsSnapshot> {
  const raw = await fetchSnapshotCore();
  writeAdminOpsSnapshotCache(raw);
  try {
    const infraRes = await api.get("/api/admin/broker-infrastructure", { timeout: INFRA_TIMEOUT_MS });
    const merged = mergePlatformMarketFeedIntoSnapshot(raw, infraRes.data?.data);
    writeAdminOpsSnapshotCache(merged);
    return merged;
  } catch {
    return raw;
  }
}

/** Primary admin layout query — snapshot HTTP first; infra merge is best-effort. */
export async function fetchAdminOpsSnapshotFast(): Promise<OpsSnapshot> {
  return fetchAdminOpsSnapshotMerged();
}

/** Snapshot-only for warm cache / prefetch (no infra round-trip). */
export async function prefetchAdminOpsSnapshot(): Promise<OpsSnapshot> {
  const raw = await fetchSnapshotCore();
  writeAdminOpsSnapshotCache(raw);
  return raw;
}
