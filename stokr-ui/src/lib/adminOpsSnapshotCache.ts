import type { OpsSnapshot } from "../components/admin/cockpit/opsTypes";

const CACHE_KEY = "stokr.admin.ops.snapshot.v1";
const MAX_AGE_MS = 30 * 60 * 1000;

type Cached = { savedAt: number; snapshot: OpsSnapshot };

export function readAdminOpsSnapshotCache(): OpsSnapshot | undefined {
  if (typeof sessionStorage === "undefined") return undefined;
  try {
    const raw = sessionStorage.getItem(CACHE_KEY);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as Cached;
    if (!parsed?.snapshot || typeof parsed.savedAt !== "number") return undefined;
    if (Date.now() - parsed.savedAt > MAX_AGE_MS) {
      sessionStorage.removeItem(CACHE_KEY);
      return undefined;
    }
    return parsed.snapshot;
  } catch {
    return undefined;
  }
}

export function writeAdminOpsSnapshotCache(snapshot: OpsSnapshot): void {
  if (typeof sessionStorage === "undefined") return;
  try {
    const payload: Cached = { savedAt: Date.now(), snapshot };
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(payload));
  } catch {
    /* quota or private mode */
  }
}
