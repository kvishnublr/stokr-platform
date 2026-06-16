import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ReplayOpsGrid } from "../../components/admin/cockpit/AdminCockpitPanels";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminReplayInfraPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Replay infrastructure</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Queue, job telemetry, and terminal diagnoses. Open{" "}
          <Link className="font-mono text-primary underline-offset-2 hover:underline" to="/admin/backfill">
            Market backfill
          </Link>{" "}
          for historical repair workflows.
        </p>
      </div>
      <ReplayOpsGrid snapshot={snapshot.data} />
    </div>
  );
}
