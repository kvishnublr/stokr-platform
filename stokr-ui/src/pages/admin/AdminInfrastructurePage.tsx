import { useQuery } from "@tanstack/react-query";
import { asRecord, fmtInt } from "../../components/admin/cockpit/opsTypes";
import { ProjectionHealthPanel, QueueDepthMonitor } from "../../components/admin/cockpit/AdminCockpitPanels";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminInfrastructurePage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  const s = snapshot.data;
  const sys = asRecord(s?.system);
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Infrastructure</h1>
        <p className="mt-1 text-xs text-muted-foreground">Queues, projections, JVM, and datastore probes from the operations snapshot.</p>
      </div>
      <div className="grid gap-3 lg:grid-cols-2">
        <QueueDepthMonitor snapshot={s} />
        <ProjectionHealthPanel snapshot={s} />
      </div>
      <div className="rounded-lg border border-border bg-card p-3 font-mono text-[10px] text-muted-foreground">
        <div className="text-xs font-semibold text-foreground">JVM / host (snapshot)</div>
        <div className="mt-2 grid gap-1 sm:grid-cols-2">
          <div>uptime: {fmtInt(sys?.uptimeSeconds)}s</div>
          <div>load: {String(sys?.osLoadAverage ?? "-")}</div>
          <div>heap used: {fmtInt(sys?.heapUsedBytes)} B</div>
          <div>heap max: {fmtInt(sys?.heapMaxBytes)} B</div>
        </div>
      </div>
    </div>
  );
}
