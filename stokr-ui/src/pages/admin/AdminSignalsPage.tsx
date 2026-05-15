import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  LiveSignalFeed,
  SignalDistributionPanel,
  SignalRoutingMonitor,
} from "../../components/admin/cockpit/AdminCockpitPanels";
import type { OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";

export function AdminSignalsPage() {
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
        <h1 className="text-xl font-semibold tracking-tight">Signal distribution</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Routing, fan-out, retries, and live signal tape. Execution timelines live under{" "}
          <Link className="font-mono text-primary underline-offset-2 hover:underline" to="/admin/execution">
            OMS / Execution
          </Link>
          .
        </p>
      </div>
      <SignalRoutingMonitor snapshot={snapshot.data} />
      <div className="grid gap-3 lg:grid-cols-2">
        <SignalDistributionPanel snapshot={snapshot.data} />
        <LiveSignalFeed snapshot={snapshot.data} />
      </div>
    </div>
  );
}
