import { useQuery } from "@tanstack/react-query";
import { OperationalHistoryStrip } from "../../components/admin/cockpit/AdminCockpitPanels";
import type { OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";

export function AdminHistoryPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: async () => {
      const res = await api.get("/api/admin/operations/snapshot");
      return res.data?.data as OpsSnapshot;
    },
    staleTime: 60_000,
  });
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Operational history</h1>
        <p className="mt-1 text-xs text-muted-foreground">Recent admin orchestration events — extend with time-series analytics as data lands.</p>
      </div>
      <OperationalHistoryStrip snapshot={snapshot.data} />
    </div>
  );
}
