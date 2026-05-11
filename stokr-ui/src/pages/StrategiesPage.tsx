import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { Filter, GitCompare, Search } from "lucide-react";
import { toast } from "sonner";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, parseAxiosMessage } from "../api/client";
import { StrategyCard, type StrategyCatalogCard } from "../components/ds/StrategyCard";
import { EmptyState } from "../components/ds/EmptyState";
import { GlassPanel } from "../components/ds/GlassPanel";
import { SkeletonCard } from "../components/ds/SkeletonLoader";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";

type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
};

export function StrategiesPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useSessionStore((s) => s.accessToken);
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [qText, setQText] = useState("");

  const q = useQuery({
    queryKey: ["strategy-catalog"],
    queryFn: async () => {
      const res = await api.get("/api/strategies/catalog?size=48");
      return res.data?.data as PageResponse<StrategyCatalogCard>;
    },
    refetchInterval: 30000,
  });

  const toggle = useMutation({
    mutationFn: async (definitionId: string) => {
      const res = await api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`);
      return res.data?.data as { subscribed: boolean; subscriptionEnabled: boolean };
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog"] });
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  const filtered = (q.data?.content ?? []).filter((s) => {
    const t = qText.trim().toLowerCase();
    if (!t) return true;
    return (
      s.code.toLowerCase().includes(t) ||
      s.name.toLowerCase().includes(t) ||
      (s.description ?? "").toLowerCase().includes(t)
    );
  });

  return (
    <div className="space-y-10">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex flex-wrap items-end justify-between gap-6">
          <div>
            <div className="flex items-center gap-2">
              <div
                className={
                  isLight
                    ? "inline-flex rounded-full border border-violet-200 bg-violet-50 px-3 py-1 text-[11px] font-black uppercase tracking-widest text-violet-700"
                    : "inline-flex rounded-full border border-purple-500/30 bg-purple-500/12 px-3 py-1 text-[11px] font-black uppercase tracking-widest text-purple-100"
                }
              >
                <GitCompare className="mr-2 h-3.5 w-3.5" />
                Institutional catalog
              </div>
            </div>
            <h1 className={isLight ? "mt-5 text-[32px] font-semibold tracking-tight text-neutral-900" : "mt-5 text-[32px] font-semibold tracking-tight text-white"}>
              Strategy workstation
            </h1>
            <p className={isLight ? "mt-3 max-w-3xl text-sm leading-relaxed text-neutral-600" : "mt-3 max-w-3xl text-sm leading-relaxed text-neutral-400"}>
              Replay-lineage aware definitions · deterministic signal schema · gated LIVE activation through broker + onboarding
              matrix. Compare risk envelopes before committing capital.
            </p>
          </div>
          <GlassPanel variant={isLight ? "light" : "dark"} className="min-w-[220px] shrink-0 p-4">
            <div className="text-[10px] font-bold uppercase tracking-widest text-neutral-500">Surface</div>
            <div className={isLight ? "mt-2 font-mono text-3xl font-semibold text-neutral-900" : "mt-2 font-mono text-3xl font-semibold text-white"}>
              {filtered.length}
            </div>
            <div className="mt-1 text-[11px] text-neutral-500">filtered rows</div>
          </GlassPanel>
        </div>
      </motion.div>

      <GlassPanel variant={isLight ? "light" : "dark"} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div className={isLight ? "flex flex-1 items-center gap-2 rounded-xl border border-neutral-200 bg-white px-3 py-2 focus-within:ring-1 focus-within:ring-blue-500/40" : "flex flex-1 items-center gap-2 rounded-xl border border-neutral-800 bg-neutral-950 px-3 py-2 focus-within:ring-1 focus-within:ring-blue-500/70"}>
          <Search className="h-4 w-4 text-neutral-500" />
          <input
            value={qText}
            onChange={(e) => setQText(e.target.value)}
            placeholder="Filter by playbook name, ticker code, rationale…"
            className={isLight ? "w-full bg-transparent text-sm text-neutral-900 outline-none placeholder:text-neutral-400" : "w-full bg-transparent text-sm text-white outline-none placeholder:text-neutral-600"}
          />
        </div>
        <button
          type="button"
          onClick={() => {
            toast.message("Comparator opened");
            navigate("/research/leaderboard");
          }}
          className={isLight ? "inline-flex items-center justify-center gap-2 rounded-xl border border-neutral-200 bg-white px-4 py-2 text-[11px] font-bold uppercase tracking-widest text-neutral-600 hover:bg-neutral-50" : "inline-flex items-center justify-center gap-2 rounded-xl border border-neutral-700 px-4 py-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400"}
        >
          <Filter className="h-3.5 w-3.5" />
          Comparator
        </button>
      </GlassPanel>

      {!token ? (
        <GlassPanel variant={isLight ? "light" : "dark"} className={isLight ? "border border-amber-200 bg-amber-50 px-5 py-3 text-sm text-amber-900" : "border border-amber-500/35 bg-amber-500/[0.06] px-5 py-3 text-sm text-amber-100"}>
          Authenticate to elevate subscriptions · catalog remains observable in read-only mode.
        </GlassPanel>
      ) : null}

      {q.isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : q.isError ? (
        <EmptyState icon={GitCompare} title="Catalog degraded" description="Retry after ensuring API availability." />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No playbook matches filters"
          description="Adjust taxonomy filters or coordinate with admins to widen catalog breadth."
        />
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {filtered.map((s, idx) => (
            <StrategyCard
              key={s.id}
              strategy={s}
              index={idx}
              variant={isLight ? "light" : "dark"}
              actionDisabled={!token}
              actionBusy={toggle.isPending}
              onToggle={() => token && toggle.mutate(s.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
