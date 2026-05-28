import { useQuery } from "@tanstack/react-query";
import { fetchAdvDashboardSnapshot } from "../api/advDashboard";
import { cn } from "../lib/utils";

function tierClass(tier: string) {
  if (tier.startsWith("A+")) return "border-emerald-400/60 bg-emerald-500/10 text-emerald-700";
  if (tier.startsWith("A")) return "border-emerald-300/50 bg-emerald-500/5 text-emerald-600";
  if (tier.startsWith("B")) return "border-amber-300/50 bg-amber-500/5 text-amber-700";
  if (tier.includes("TRAP") || tier.includes("HIGH RISK")) return "border-rose-300/50 bg-rose-500/5 text-rose-700";
  return "border-slate-300/40 bg-slate-500/5 text-slate-600";
}

export function AdvDashboardPage() {
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ["adv-dashboard-snapshot"],
    queryFn: fetchAdvDashboardSnapshot,
    refetchInterval: 15_000,
  });

  return (
    <div className="mx-auto max-w-7xl space-y-6 p-4 md:p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-violet-600">Data-First Intelligence</p>
          <h1 className="text-2xl font-bold text-slate-900">ADV Dashboard</h1>
          <p className="mt-1 max-w-2xl text-sm text-slate-600">
            Confidence-driven intraday intelligence — structure, volume, regime context. Not indicator noise.
          </p>
        </div>
        <button
          type="button"
          onClick={() => refetch()}
          className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 shadow-sm hover:bg-slate-50"
        >
          {isFetching ? "Refreshing…" : "Refresh"}
        </button>
      </header>

      {isLoading && <div className="rounded-xl border bg-white p-8 text-center text-slate-500">Loading intelligence snapshot…</div>}
      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">
          Failed to load ADV dashboard. Ensure market session feeds are active.
        </div>
      )}

      {data && (
        <>
          <section className="grid gap-4 md:grid-cols-4">
            <MetricCard label="Market Regime" value={data.marketRegime.replace(/_/g, " ")} hint={data.regimeNarrative} />
            <MetricCard label="Active Setups" value={String(data.metrics.activeSetups ?? 0)} hint="High-quality ranked only" />
            <MetricCard label="Top Score" value={String(data.metrics.topScore ?? "—")} hint="Best conviction setup" />
            <MetricCard label="Stocks Tracked" value={String(data.metrics.stocksTracked ?? 0)} hint="Live tick universe" />
          </section>

          <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-800">Implementation Principles</h2>
            <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-600">
              {data.principles.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </section>

          <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="border-b border-slate-100 px-4 py-3">
              <h2 className="font-semibold text-slate-900">High-Conviction Setups</h2>
              <p className="text-xs text-slate-500">Ranked by quality score — weak setups filtered</p>
            </div>
            {data.setups.length === 0 ? (
              <div className="p-8 text-center text-sm text-slate-500">
                No active setups meeting quality threshold. Market may be closed or in low-participation regime.
              </div>
            ) : (
              <div className="divide-y divide-slate-100">
                {data.setups.map((s) => (
                  <article key={`${s.symbol}-${s.setupType}`} className="grid gap-3 p-4 md:grid-cols-12 md:items-start">
                    <div className="md:col-span-2">
                      <div className="font-mono text-lg font-bold text-slate-900">{s.symbol}</div>
                      <span className={cn("mt-1 inline-block rounded-full border px-2 py-0.5 text-xs font-semibold", tierClass(s.qualityTier))}>
                        {s.qualityTier}
                      </span>
                    </div>
                    <div className="md:col-span-1 text-center">
                      <div className="text-2xl font-bold text-violet-600">{s.confidenceScore}</div>
                      <div className="text-[10px] uppercase text-slate-400">Score</div>
                    </div>
                    <div className="md:col-span-5 space-y-1">
                      <p className="text-sm font-medium text-slate-800">{s.whyThisTrade}</p>
                      <p className="text-xs text-amber-700">Risk: {s.riskNote}</p>
                      <div className="flex flex-wrap gap-1 pt-1">
                        {s.badges.map((b) => (
                          <span key={b} className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600">
                            {b}
                          </span>
                        ))}
                      </div>
                    </div>
                    <div className="md:col-span-4 grid grid-cols-3 gap-2 text-xs text-slate-600">
                      <div>Entry<br /><span className="font-mono font-semibold text-slate-900">{s.entryPrice ?? "—"}</span></div>
                      <div>Target<br /><span className="font-mono font-semibold text-emerald-700">{s.targetPrice ?? "—"}</span></div>
                      <div>Stop<br /><span className="font-mono font-semibold text-rose-700">{s.stopLoss ?? "—"}</span></div>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function MetricCard({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-1 text-xl font-bold text-slate-900">{value}</div>
      <div className="mt-1 text-xs text-slate-500">{hint}</div>
    </div>
  );
}
