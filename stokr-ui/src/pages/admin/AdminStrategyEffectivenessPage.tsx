import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

type Scorecard = {
  strategyKey: string;
  signalsGenerated: number;
  signalsExecuted: number;
  targetHits: number;
  stopLossHits: number;
  protectionExits: number;
  feedProtection: number;
  expired: number;
  rejected: number;
  running: number;
  open: number;
  closed: number;
  winRate: number;
  lossRate: number;
  targetHitPct: number;
  slHitPct: number;
  protectionExitPct: number;
  avgHoldSeconds: number;
  avgConfidencePct: number;
  avgMae: number;
  avgMfe: number;
  profitFactor: number;
  expectancy: number;
  confidenceV2Count: number;
  confidenceNullCount: number;
};

type Report = {
  fromDate: string;
  toDate: string;
  v8CutoffInstant: string;
  scorecards: Scorecard[];
  leaderboard: Array<{
    strategyKey: string;
    expectancy: number;
    profitFactor: number;
    targetHitRate: number;
    riskAdjustedReturn: number;
    confidenceAccuracy: number;
    rankScore: number;
  }>;
  globalConfidenceBuckets: Array<{
    bucket: string;
    signals: number;
    wins: number;
    losses: number;
    protectionExits: number;
    targetHits: number;
    slHits: number;
    winRate: number;
  }>;
  protectionByStrategy: Record<
    string,
    {
      protectedTrades: number;
      wouldHaveHitTarget: number;
      wouldHaveHitStop: number;
      missedProfit: number;
      savedLoss: number;
      protectionEffectivenessPct: number;
    }
  >;
  liveReadiness: Array<{ strategyKey: string; tier: string; reason: string; leaderboardRank: number }>;
  v8Comparison: {
    preSignalCount: number;
    postSignalCount: number;
    preTargetHits: number;
    postTargetHits: number;
    preSlHits: number;
    postSlHits: number;
    preProtectionExits: number;
    postProtectionExits: number;
    preAvgHoldSeconds: number;
    postAvgHoldSeconds: number;
    preConfidencePopulated: number;
    postConfidencePopulated: number;
    postConfidenceV2: number;
    preMfeTracked: number;
    postMfeTracked: number;
  };
};

function tierTone(tier: string, isLight: boolean) {
  if (tier === "LIVE_READY" || tier === "CAPITAL_ALLOCATION_READY") {
    return isLight ? "text-emerald-700 bg-emerald-50 border-emerald-200" : "text-emerald-400 bg-emerald-500/10 border-emerald-500/30";
  }
  if (tier === "NOT_READY") {
    return isLight ? "text-rose-700 bg-rose-50 border-rose-200" : "text-rose-400 bg-rose-500/10 border-rose-500/30";
  }
  return isLight ? "text-amber-800 bg-amber-50 border-amber-200" : "text-amber-300 bg-amber-500/10 border-amber-500/30";
}

export function AdminStrategyEffectivenessPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [from, setFrom] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() - 30);
    return d.toISOString().slice(0, 10);
  });
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));

  const q = useQuery({
    queryKey: ["strategy-effectiveness", from, to],
    queryFn: async () => {
      const res = await api.get("/api/admin/strategy-effectiveness", { params: { from, to } });
      return res.data?.data as Report;
    },
    refetchInterval: 60_000,
  });

  const r = q.data;
  const active = (r?.scorecards ?? []).filter((s) => s.signalsGenerated > 0);

  return (
    <AdminPageShell
      isLight={isLight}
      title="Strategy Effectiveness"
      subtitle="Production outcomes only — signals, execution, protection, confidence, V8 comparison"
      actions={
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <label className="flex items-center gap-1">
            From
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="rounded border px-2 py-1 font-mono"
            />
          </label>
          <label className="flex items-center gap-1">
            To
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="rounded border px-2 py-1 font-mono"
            />
          </label>
        </div>
      }
    >
      {q.isLoading && <p className="text-sm text-neutral-500">Loading production analytics…</p>}

      {r && (
        <>
          <AdminSection isLight={isLight} title="V8 pre vs post (production)">
            <AdminPanel isLight={isLight}>
              <p className="mb-2 text-xs text-neutral-500">Cutoff: {r.v8CutoffInstant}</p>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                {[
                  { label: "Signals pre / post", value: `${r.v8Comparison.preSignalCount} / ${r.v8Comparison.postSignalCount}` },
                  { label: "Target hits pre / post", value: `${r.v8Comparison.preTargetHits} / ${r.v8Comparison.postTargetHits}` },
                  { label: "SL hits pre / post", value: `${r.v8Comparison.preSlHits} / ${r.v8Comparison.postSlHits}` },
                  { label: "Protection pre / post", value: `${r.v8Comparison.preProtectionExits} / ${r.v8Comparison.postProtectionExits}` },
                  { label: "Avg hold (s) pre / post", value: `${r.v8Comparison.preAvgHoldSeconds} / ${r.v8Comparison.postAvgHoldSeconds}` },
                  { label: "Confidence populated pre / post", value: `${r.v8Comparison.preConfidencePopulated} / ${r.v8Comparison.postConfidencePopulated}` },
                  { label: "CONFIDENCE_V2 post", value: r.v8Comparison.postConfidenceV2 },
                  { label: "MFE tracked pre / post", value: `${r.v8Comparison.preMfeTracked} / ${r.v8Comparison.postMfeTracked}` },
                ].map((m) => (
                  <div key={m.label} className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}>
                    <p className="text-[10px] uppercase text-neutral-500">{m.label}</p>
                    <p className="font-mono text-sm font-semibold">{m.value}</p>
                  </div>
                ))}
              </div>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Live readiness">
            <AdminPanel isLight={isLight}>
              <div className="space-y-2">
                {r.liveReadiness.map((lr) => (
                  <div
                    key={lr.strategyKey}
                    className={cn("rounded-lg border px-3 py-2", tierTone(lr.tier, isLight))}
                  >
                    <span className="font-semibold">{lr.strategyKey}</span>
                    <span className="mx-2 font-mono text-[10px]">{lr.tier}</span>
                    <p className="mt-1 text-xs opacity-90">{lr.reason}</p>
                  </div>
                ))}
              </div>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Leaderboard">
            <AdminPanel isLight={isLight}>
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-neutral-700 text-neutral-400">
                    <th className="p-2">#</th>
                    <th className="p-2">Strategy</th>
                    <th className="p-2">Expectancy</th>
                    <th className="p-2">PF</th>
                    <th className="p-2">Target %</th>
                    <th className="p-2">Risk-adj</th>
                  </tr>
                </thead>
                <tbody>
                  {r.leaderboard.map((e, idx) => (
                    <tr key={e.strategyKey} className="border-b border-neutral-800/50">
                      <td className="p-2">{idx + 1}</td>
                      <td className="p-2 font-medium">{e.strategyKey}</td>
                      <td className="p-2 font-mono">{e.expectancy}</td>
                      <td className="p-2 font-mono">{e.profitFactor}</td>
                      <td className="p-2 font-mono">{e.targetHitRate}%</td>
                      <td className="p-2 font-mono">{e.riskAdjustedReturn}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Strategy scorecards">
            <AdminPanel isLight={isLight}>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-neutral-700 text-neutral-400">
                      <th className="p-2">Strategy</th>
                      <th className="p-2">Gen</th>
                      <th className="p-2">Exec</th>
                      <th className="p-2">Target</th>
                      <th className="p-2">SL</th>
                      <th className="p-2">Protect</th>
                      <th className="p-2">Win%</th>
                      <th className="p-2">Hold s</th>
                      <th className="p-2">Conf</th>
                      <th className="p-2">MFE/MAE</th>
                      <th className="p-2">PF</th>
                    </tr>
                  </thead>
                  <tbody>
                    {active.map((s) => (
                      <tr key={s.strategyKey} className="border-b border-neutral-800/50">
                        <td className="p-2 font-medium">{s.strategyKey}</td>
                        <td className="p-2">{s.signalsGenerated}</td>
                        <td className="p-2">{s.signalsExecuted}</td>
                        <td className="p-2">{s.targetHits}</td>
                        <td className="p-2">{s.stopLossHits}</td>
                        <td className="p-2">{s.protectionExits}</td>
                        <td className="p-2">{s.winRate}%</td>
                        <td className="p-2">{s.avgHoldSeconds}</td>
                        <td className="p-2">
                          {s.confidenceNullCount > 0 ? (
                            <span className="text-rose-500">{s.confidenceNullCount} null</span>
                          ) : (
                            <span>{s.avgConfidencePct}%</span>
                          )}
                        </td>
                        <td className="p-2 font-mono">
                          {s.avgMfe}/{s.avgMae}
                        </td>
                        <td className="p-2">{s.profitFactor}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Confidence effectiveness (all strategies)">
            <AdminPanel isLight={isLight}>
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-neutral-700 text-neutral-400">
                    <th className="p-2">Bucket</th>
                    <th className="p-2">Signals</th>
                    <th className="p-2">Target</th>
                    <th className="p-2">SL</th>
                    <th className="p-2">Protect</th>
                    <th className="p-2">Win rate</th>
                  </tr>
                </thead>
                <tbody>
                  {r.globalConfidenceBuckets.map((b) => (
                    <tr key={b.bucket} className="border-b border-neutral-800/50">
                      <td className="p-2 font-mono">{b.bucket}</td>
                      <td className="p-2">{b.signals}</td>
                      <td className="p-2">{b.targetHits}</td>
                      <td className="p-2">{b.slHits}</td>
                      <td className="p-2">{b.protectionExits}</td>
                      <td className="p-2">{b.winRate}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Protection impact">
            <AdminPanel isLight={isLight}>
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-neutral-700 text-neutral-400">
                    <th className="p-2">Strategy</th>
                    <th className="p-2">Protected</th>
                    <th className="p-2">Would target</th>
                    <th className="p-2">Would SL</th>
                    <th className="p-2">Effectiveness</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(r.protectionByStrategy)
                    .filter(([, p]) => p.protectedTrades > 0)
                    .map(([key, p]) => (
                      <tr key={key} className="border-b border-neutral-800/50">
                        <td className="p-2 font-medium">{key}</td>
                        <td className="p-2">{p.protectedTrades}</td>
                        <td className="p-2">{p.wouldHaveHitTarget}</td>
                        <td className="p-2">{p.wouldHaveHitStop}</td>
                        <td className="p-2">{p.protectionEffectivenessPct}%</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </AdminPanel>
          </AdminSection>
        </>
      )}
    </AdminPageShell>
  );
}
