import { api } from "../api/client";
import type { OpsSnapshot } from "../components/admin/cockpit/opsTypes";
import { mergePlatformMarketFeedIntoSnapshot } from "./adminOpsSnapshotMerge";

/**
 * Operations snapshot plus `platformMarketFeed` from `/api/admin/broker-infrastructure`
 * so readiness and Broker Infrastructure agree on platform OAuth session state.
 */
export async function fetchAdminOpsSnapshotMerged(): Promise<OpsSnapshot> {
  const [snapRes, infraRes] = await Promise.allSettled([
    api.get("/api/admin/operations/snapshot"),
    api.get("/api/admin/broker-infrastructure"),
  ]);
  if (snapRes.status !== "fulfilled") {
    throw snapRes.reason;
  }
  const raw = snapRes.value.data?.data as OpsSnapshot;
  if (infraRes.status !== "fulfilled") {
    return raw;
  }
  const infra = infraRes.value.data?.data;
  return mergePlatformMarketFeedIntoSnapshot(raw, infra);
}
