import { useQuery } from "@tanstack/react-query";
import { Activity, Radio } from "lucide-react";
import { api } from "../api/client";

type OpsStatus = {
  registeredUsers: number;
  runningStrategies: number;
  websocketUsersApprox: number;
  rabbitQueues: Record<string, Record<string, string>>;
};

type ReadinessSnapshot = {
  checks: Record<string, { ok: boolean; detail: string }>;
  blocking: boolean;
};

export function AdminOpsPage() {
  const q = useQuery({
    queryKey: ["admin-ops-status"],
    queryFn: async () => {
      const res = await api.get("/api/admin/ops/status");
      return res.data?.data as OpsStatus;
    },
  });

  const readiness = useQuery({
    queryKey: ["admin-readiness"],
    queryFn: async () => {
      const res = await api.get("/api/admin/readiness");
      return res.data?.data as ReadinessSnapshot;
    },
  });

  const d = q.data;
  const r = readiness.data;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Operations center</h1>
        <p className="mt-1 text-sm text-neutral-400">Live counts, queue introspection, and websocket footprint.</p>
      </div>

      <div
        className={
          "rounded-2xl border p-5 " +
          (r?.blocking ? "border-red-900/80 bg-red-950/30" : "border-neutral-800 bg-neutral-950/60")
        }
      >
        <div className="text-sm font-medium text-white">Live-trading readiness (pre-broker)</div>
        {readiness.isLoading ? (
          <div className="mt-3 text-sm text-neutral-400">Loading readiness…</div>
        ) : readiness.isError ? (
          <div className="mt-3 text-sm text-red-400">Could not load readiness (admin only).</div>
        ) : (
          <ul className="mt-4 space-y-2 text-sm">
            {r
              ? Object.entries(r.checks).map(([k, v]) => (
                  <li key={k} className="flex flex-wrap justify-between gap-2 border-b border-neutral-900 pb-2 last:border-0">
                    <span className="font-mono text-xs text-neutral-400">{k}</span>
                    <span className={v.ok ? "text-emerald-400" : "text-amber-300"}>{v.detail}</span>
                  </li>
                ))
              : null}
          </ul>
        )}
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">
            <Activity className="h-4 w-4" />
            Users
          </div>
          <div className="mt-3 font-mono text-3xl text-white">{d?.registeredUsers ?? "—"}</div>
        </div>
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">
            <Radio className="h-4 w-4" />
            Running strategies
          </div>
          <div className="mt-3 font-mono text-3xl text-white">{d?.runningStrategies ?? "—"}</div>
        </div>
        <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
          <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">WebSocket users</div>
          <div className="mt-3 font-mono text-3xl text-white">
            {d?.websocketUsersApprox === -1 ? "n/a" : (d?.websocketUsersApprox ?? "—")}
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5">
        <div className="text-sm font-medium text-white">RabbitMQ queues</div>
        <pre className="mt-4 max-h-[420px] overflow-auto rounded-lg bg-neutral-950 p-4 text-[11px] text-neutral-300">
          {JSON.stringify(d?.rabbitQueues ?? {}, null, 2)}
        </pre>
      </div>
    </div>
  );
}
