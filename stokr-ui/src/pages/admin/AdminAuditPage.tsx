import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { OperationalHistoryStrip } from "../../components/admin/cockpit/AdminCockpitPanels";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminAuditPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Audit & governance</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Accountability trail (broker actions, feed pauses, session tests). User lifecycle changes remain in{" "}
          <Link className="font-mono text-primary underline-offset-2 hover:underline" to="/admin/users">
            User management
          </Link>
          .
        </p>
      </div>
      <OperationalHistoryStrip snapshot={snapshot.data} />
    </div>
  );
}
