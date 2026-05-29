import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

type StrategyDiagnostics = {
  productionSignalCount: number;
  confidenceNullCount: number;
  confidenceV2Count: number;
  byOwnerType: Record<string, number>;
  byLifecycleStatus: Record<string, number>;
  avgConfidenceScore: number | null;
  avgProbability: number | null;
  recentProductionSignals: Array<{
    signalId: string;
    strategyName: string;
    symbol: string;
    ownerType: string;
    lifecycleStatus: string;
    confidenceScore: number;
    probability: number;
    tradeQuality: string;
    confidenceVersion: string;
    breakdownPresent: boolean;
    entryPrice: number;
    targetPrice: number;
    stopPrice: number;
    unifiedAiScore: number;
  }>;
};

export function AdminStrategyDiagnosticsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const q = useQuery({
    queryKey: ["admin-strategy-diagnostics"],
    queryFn: async () =>
      (await api.get("/api/admin/diagnostics/strategy")).data?.data as StrategyDiagnostics,
    refetchInterval: 30_000,
  });
  const d = q.data;

  return (
    <AdminPageShell
      isLight={isLight}
      title="Strategy Diagnostics"
      subtitle="Production confidence persistence vs unified AI score"
    >
      <AdminSection isLight={isLight} title="Confidence health">
        <AdminPanel isLight={isLight}>
          {d && (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              {[
                { label: "Production signals (24h)", value: d.productionSignalCount },
                { label: "Confidence NULL", value: d.confidenceNullCount, warn: d.confidenceNullCount > 0 },
                { label: "CONFIDENCE_V2", value: d.confidenceV2Count },
                { label: "Avg confidence", value: d.avgConfidenceScore ?? "—" },
              ].map((m) => (
                <div
                  key={m.label}
                  className={cn(
                    "rounded-lg border px-3 py-2",
                    m.warn ? "border-rose-500/40 bg-rose-500/10" : isLight ? "border-neutral-200" : "border-neutral-800",
                  )}
                >
                  <p className="text-[10px] uppercase text-neutral-500">{m.label}</p>
                  <p className="font-mono text-sm font-semibold">{m.value}</p>
                </div>
              ))}
            </div>
          )}
        </AdminPanel>
      </AdminSection>
      <AdminSection isLight={isLight} title="Recent production signals">
        <AdminPanel isLight={isLight}>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-neutral-700 text-neutral-400">
                  <th className="p-2">Symbol</th>
                  <th className="p-2">Owner</th>
                  <th className="p-2">DB conf</th>
                  <th className="p-2">AI score</th>
                  <th className="p-2">Version</th>
                  <th className="p-2">Lifecycle</th>
                  <th className="p-2">Match</th>
                </tr>
              </thead>
              <tbody>
                {(d?.recentProductionSignals ?? []).map((r) => {
                  const dbPct =
                    r.confidenceScore != null && r.confidenceScore <= 1
                      ? Math.round(r.confidenceScore * 100)
                      : r.confidenceScore;
                  const match = dbPct === r.unifiedAiScore;
                  return (
                    <tr key={r.signalId} className="border-b border-neutral-800/60">
                      <td className="p-2">{r.symbol}</td>
                      <td className="p-2">{r.ownerType}</td>
                      <td className="p-2">{dbPct ?? "NULL"}</td>
                      <td className="p-2">{r.unifiedAiScore}</td>
                      <td className="p-2">{r.confidenceVersion ?? "—"}</td>
                      <td className="p-2">{r.lifecycleStatus}</td>
                      <td className={cn("p-2", match ? "text-emerald-500" : "text-rose-500")}>
                        {match ? "OK" : "MISMATCH"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </AdminPanel>
      </AdminSection>
    </AdminPageShell>
  );
}
