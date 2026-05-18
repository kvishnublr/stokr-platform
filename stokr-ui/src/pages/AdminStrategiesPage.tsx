import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, EyeOff, Layers } from "lucide-react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";

type StrategyRow = {
  id: string;
  code: string;
  displayName: string | null;
  description: string | null;
  enabled: boolean;
  visibleToUsers: boolean;
  riskLevel: string;
  createdAt: string;
};

type PageWrap = { content: StrategyRow[] };

export function AdminStrategiesPage() {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["admin-strategies"],
    queryFn: async () => {
      const res = await api.get("/api/admin/strategies?size=50");
      return res.data?.data as PageWrap;
    },
  });

  const patch = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) => {
      await api.patch(`/api/admin/strategies/${payload.id}`, payload.body);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-strategies"] });
      void qc.invalidateQueries({ queryKey: ["strategy-catalog"] });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Strategy catalog</h1>
        <p className="mt-2 text-sm text-neutral-400">
          Control which definitions are globally enabled and visible to traders.
        </p>
      </div>

      {/* Quick nav to new catalog management pages */}
      <div className="flex flex-wrap gap-2">
        <Link
          to="/admin/strategy-catalog"
          className="rounded-lg border border-violet-700/40 bg-violet-900/20 px-3 py-1.5 text-xs font-semibold text-violet-300 hover:bg-violet-800/30"
        >
          + Create / manage strategies
        </Link>
        <Link
          to="/admin/universe-groups"
          className="rounded-lg border border-blue-700/40 bg-blue-900/20 px-3 py-1.5 text-xs font-semibold text-blue-300 hover:bg-blue-800/30"
        >
          Universe groups
        </Link>
        <Link
          to="/admin/runtime-bindings"
          className="rounded-lg border border-emerald-700/40 bg-emerald-900/20 px-3 py-1.5 text-xs font-semibold text-emerald-300 hover:bg-emerald-800/30"
        >
          Runtime bindings
        </Link>
      </div>

      {q.isLoading ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {[1, 2].map((i) => (
            <div
              key={i}
              className="h-40 animate-pulse rounded-2xl border border-neutral-900 bg-neutral-900/40"
            />
          ))}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-900/40 bg-red-950/30 px-4 py-3 text-sm text-red-200">
          Could not load strategy definitions. {parseAxiosMessage(q.error)}
        </div>
      ) : (q.data?.content ?? []).length === 0 ? (
        <div className="rounded-xl border border-neutral-800 bg-neutral-900/40 px-4 py-8 text-center text-sm text-neutral-400">
          No strategy definitions in the database yet. Run migrations / seed data, then refresh.
        </div>
      ) : (
      <div className="grid gap-4 lg:grid-cols-2">
        {(q.data?.content ?? []).map((s) => (
          <div
            key={s.id}
            className="rounded-2xl border border-neutral-800 bg-neutral-900/40 p-5 shadow-inner"
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2">
                  <Layers className="h-5 w-5 text-violet-400" />
                  <span className="font-semibold text-white">{s.displayName ?? s.code}</span>
                </div>
                <div className="mt-1 font-mono text-xs text-neutral-500">{s.code}</div>
                <p className="mt-3 text-sm text-neutral-400">{s.description ?? "-"}</p>
              </div>
              <span className="rounded-full bg-neutral-800 px-2 py-0.5 text-[10px] font-bold uppercase text-neutral-300">
                {s.riskLevel}
              </span>
            </div>

            <div className="mt-5 flex flex-wrap gap-2">
              <button
                type="button"
                className={cn(
                  "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold",
                  s.enabled ? "bg-emerald-600 text-white" : "bg-neutral-800 text-neutral-300",
                )}
                onClick={() => patch.mutate({ id: s.id, body: { enabled: !s.enabled } })}
              >
                {s.enabled ? <Eye className="h-3.5 w-3.5" /> : <EyeOff className="h-3.5 w-3.5" />}
                {s.enabled ? "Enabled" : "Disabled"}
              </button>
              <button
                type="button"
                className={cn(
                  "rounded-lg px-3 py-1.5 text-xs font-semibold",
                  s.visibleToUsers ? "bg-blue-600 text-white" : "bg-neutral-800 text-neutral-400",
                )}
                onClick={() => patch.mutate({ id: s.id, body: { visibleToUsers: !s.visibleToUsers } })}
              >
                {s.visibleToUsers ? "Visible" : "Hidden"}
              </button>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  );
}
