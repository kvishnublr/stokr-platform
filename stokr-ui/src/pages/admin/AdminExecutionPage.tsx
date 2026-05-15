import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  ExecutionTimelinePanel,
  OMSLatencyMonitor,
} from "../../components/admin/cockpit/AdminCockpitPanels";
import type { OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";

export function AdminExecutionPage() {
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
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">OMS / Execution</h1>
          <p className="mt-1 text-xs text-muted-foreground">Throughput, rejects, latency, and per-order execution timeline.</p>
        </div>
        <Link
          to="/admin/oms"
          className="rounded-md border border-border bg-card px-3 py-1.5 font-mono text-[10px] font-semibold text-primary hover:bg-background/80"
        >
          Order grid →
        </Link>
      </div>
      <OMSLatencyMonitor snapshot={snapshot.data} />
      <ExecutionTimelinePanel />
    </div>
  );
}
