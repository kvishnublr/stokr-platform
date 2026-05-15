import { useQuery } from "@tanstack/react-query";
import { BackfillOperationsPanel } from "../../components/admin/cockpit/AdminCockpitPanels";
import type { OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";

export function AdminBackfillPage() {
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
        <h1 className="text-xl font-semibold tracking-tight">Backfill operations</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Replay queue telemetry and broker-feed coupling — job orchestration APIs ship separately.
        </p>
      </div>
      <BackfillOperationsPanel snapshot={snapshot.data} />
    </div>
  );
}
