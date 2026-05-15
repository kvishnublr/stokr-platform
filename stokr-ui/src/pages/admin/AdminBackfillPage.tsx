import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { BackfillOperationsPanel } from "../../components/admin/cockpit/AdminCockpitPanels";
import { api, parseAxiosMessage } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminBackfillPage() {
  const qc = useQueryClient();
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  const jobs = useQuery({
    queryKey: ["admin-backfill-jobs"],
    queryFn: async () => {
      const res = await api.get("/api/admin/backfill/jobs?limit=25");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as Array<Record<string, unknown>>;
    },
    refetchInterval: 10_000,
  });

  const cancelJob = useMutation({
    mutationFn: async (id: string) => api.post(`/api/admin/backfill/jobs/${id}/cancel`),
    onSuccess: async () => {
      toast.success("Backfill job cancelled");
      await qc.invalidateQueries({ queryKey: ["admin-backfill-jobs"] });
      await qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const rerunJob = useMutation({
    mutationFn: async (id: string) => api.post(`/api/admin/backfill/jobs/${id}/rerun`),
    onSuccess: async () => {
      toast.success("Backfill rerun queued");
      await qc.invalidateQueries({ queryKey: ["admin-backfill-jobs"] });
      await qc.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
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
      <div className="rounded-xl border border-border bg-card p-3">
        <div className="mb-2 text-sm font-semibold">Recent backfill jobs</div>
        <div className="max-h-96 overflow-auto rounded border border-border">
          <table className="w-full border-collapse text-left text-xs">
            <thead className="sticky top-0 bg-card">
              <tr>
                <th className="border-b border-border px-2 py-1">Job</th>
                <th className="border-b border-border px-2 py-1">Status</th>
                <th className="border-b border-border px-2 py-1">Progress</th>
                <th className="border-b border-border px-2 py-1">Diagnosis</th>
                <th className="border-b border-border px-2 py-1">Updated</th>
                <th className="border-b border-border px-2 py-1">Actions</th>
              </tr>
            </thead>
            <tbody>
              {(jobs.data ?? []).map((r) => {
                const id = String(r.id ?? "");
                const status = String(r.status ?? "");
                return (
                  <tr key={id} className="border-b border-border/70">
                    <td className="px-2 py-1 font-mono">{id.slice(0, 8)}</td>
                    <td className="px-2 py-1">{status}</td>
                    <td className="px-2 py-1">{String(r.progress ?? 0)}%</td>
                    <td className="px-2 py-1">{String(r.replayDiagnosis ?? "-")}</td>
                    <td className="px-2 py-1">{String(r.updatedAt ?? "-")}</td>
                    <td className="px-2 py-1">
                      <div className="flex gap-1">
                        <button
                          type="button"
                          disabled={cancelJob.isPending || !(status === "QUEUED" || status === "RUNNING")}
                          onClick={() => cancelJob.mutate(id)}
                          className="rounded border border-border px-2 py-0.5 disabled:opacity-40"
                        >
                          Cancel
                        </button>
                        <button
                          type="button"
                          disabled={rerunJob.isPending}
                          onClick={() => rerunJob.mutate(id)}
                          className="rounded border border-border px-2 py-0.5 disabled:opacity-40"
                        >
                          Rerun
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
              {jobs.data && jobs.data.length === 0 ? (
                <tr>
                  <td className="px-2 py-3 text-muted-foreground" colSpan={6}>
                    No backfill jobs found.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
