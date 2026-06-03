import { api } from "../api/client";
import type { OpsSnapshot } from "../components/admin/cockpit/opsTypes";
import { mergePlatformMarketFeedIntoSnapshot } from "./adminOpsSnapshotMerge";

const SNAPSHOT_TIMEOUT_MS = 25_000;
const INFRA_TIMEOUT_MS = 10_000;

/**
 * Operations snapshot plus optional `platformMarketFeed` from broker-infrastructure.
 * Snapshot is fetched first with a hard timeout so the readiness strip never spins forever.
 */
export async function fetchAdminOpsSnapshotMerged(): Promise<OpsSnapshot> {
  const snapRes = await api.get("/api/admin/operations/snapshot", { timeout: SNAPSHOT_TIMEOUT_MS });
  const raw = snapRes.data?.data as OpsSnapshot;
  try {
    const infraRes = await api.get("/api/admin/broker-infrastructure", { timeout: INFRA_TIMEOUT_MS });
    return mergePlatformMarketFeedIntoSnapshot(raw, infraRes.data?.data);
  } catch {
    return raw;
  }
}
