import { useQuery } from "@tanstack/react-query";
import { TraderExecutionHealthGrid } from "../../components/admin/cockpit/AdminCockpitPanels";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminTraderHealthPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Trader execution health</h1>
        <p className="mt-1 text-xs text-muted-foreground">Per-trader broker connectivity, routing aggregates, and last OMS activity.</p>
      </div>
      <TraderExecutionHealthGrid snapshot={snapshot.data} />
    </div>
  );
}
