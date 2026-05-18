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

const RISK_COLOR: Record<string, string> = {
  HIGH: "bg-red-500/15 text-red-500",
  MEDIUM: "bg-amber-500/15 text-amber-600 dark:text-amber-400",
  LOW: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400",
};

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
    <div className="space-y-5 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Strategies</h1>
        <p className="mt-0.5 text-xs text-muted-foreground">
          Control which definitions are globally enabled and visible to traders.
        </p>
      </div>

      {/* Quick nav */}
      <div className="flex flex-wrap gap-2">
        <Link
          to="/admin/strategy-catalog"
          className="rounded-lg border border-violet-500/30 bg-violet-500/10 px-3 py-1.5 text-xs font-semibold text-violet-600 hover:bg-violet-500/20 dark:text-violet-300"
        >
          + Create / manage strategies
        </Link>
        <Link
          to="/admin/universe-groups"
          className="rounded-lg border border-blue-500/30 bg-blue-500/10 px-3 py-1.5 text-xs font-semibold text-blue-600 hover:bg-blue-500/20 dark:text-blue-300"
        >
          Universe groups
        </Link>
        <Link
          to="/admin/runtime-bindings"
          className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-xs font-semibold text-emerald-600 hover:bg-emerald-500/20 dark:text-emerald-300"
        >
          Runtime bindings
        </Link>
      </div>

      {q.isLoading ? (
        <div className="grid gap-3 lg:grid-cols-2">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-32 animate-pulse rounded-xl border border-border bg-muted" />
          ))}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 dark:border-red-900/40 dark:bg-red-950/30 dark:text-red-300">
          Could not load strategy definitions. {parseAxiosMessage(q.error)}
        </div>
      ) : (q.data?.content ?? []).length === 0 ? (
        <div className="rounded-xl border border-border bg-muted/30 px-4 py-8 text-center text-sm text-muted-foreground">
          No strategy definitions yet. Use "Create / manage strategies" above.
        </div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {(q.data?.content ?? []).map((s) => (
            <div
              key={s.id}
              className={cn(
                "rounded-xl border bg-card p-4 shadow-sm transition-colors",
                s.enabled ? "border-border" : "border-border opacity-60",
              )}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <Layers className="h-4 w-4 shrink-0 text-violet-500" />
                    <span className="font-semibold text-foreground truncate">{s.displayName ?? s.code}</span>
                  </div>
                  <div className="mt-0.5 font-mono text-[11px] text-muted-foreground">{s.code}</div>
                  {s.description && (
                    <p className="mt-2 text-xs text-muted-foreground line-clamp-2">{s.description}</p>
                  )}
                </div>
                <span className={cn(
                  "shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase",
                  RISK_COLOR[s.riskLevel] ?? "bg-muted text-muted-foreground",
                )}>
                  {s.riskLevel}
                </span>
              </div>

              <div className="mt-4 flex flex-wrap gap-2">
                <button
                  type="button"
                  className={cn(
                    "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors",
                    s.enabled
                      ? "bg-emerald-500/15 text-emerald-600 hover:bg-emerald-500/25 dark:text-emerald-400"
                      : "bg-muted text-muted-foreground hover:bg-muted/70",
                  )}
                  onClick={() => patch.mutate({ id: s.id, body: { enabled: !s.enabled } })}
                >
                  {s.enabled ? <Eye className="h-3.5 w-3.5" /> : <EyeOff className="h-3.5 w-3.5" />}
                  {s.enabled ? "Enabled" : "Disabled"}
                </button>
                <button
                  type="button"
                  className={cn(
                    "rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors",
                    s.visibleToUsers
                      ? "bg-blue-500/15 text-blue-600 hover:bg-blue-500/25 dark:text-blue-400"
                      : "bg-muted text-muted-foreground hover:bg-muted/70",
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
