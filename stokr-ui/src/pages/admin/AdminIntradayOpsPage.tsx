import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, parseAxiosMessage } from "../../api/client";
import { INTRADAY_SETUPS } from "../../lib/intradaySetups";
import { useUiThemeStore } from "../../state/uiTheme";

type StrategyAuditRow = {
  strategyKey: string;
  displayName: string | null;
  symbolsTotal: number;
  symbolsReady: number;
  ready: boolean;
  status: string;
  blockers: Array<{ symbol: string; state: string; detail: string }>;
};

type CoverageRow = {
  symbol: string;
  timeframe: string;
  replayReadiness: string;
  scannerReadiness: string;
  freshness: string;
  completeness: string;
  gapCount: number;
};

export function AdminIntradayOpsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const now = new Date();
  const toIso = now.toISOString();
  const fromIso = new Date(now.getTime() - 5 * 24 * 60 * 60 * 1000).toISOString();

  const opsQ = useQuery({
    queryKey: ["admin-ops-snapshot-intraday"],
    queryFn: async () => (await api.get("/api/admin/operations/snapshot")).data?.data as Record<string, any>,
    refetchInterval: 15000,
  });
  const coverageQ = useQuery({
    queryKey: ["admin-backfill-coverage-intraday"],
    queryFn: async () => (await api.get("/api/admin/market/backfill/coverage")).data?.data as CoverageRow[],
    refetchInterval: 20000,
  });
  const auditQ = useQuery({
    queryKey: ["admin-intraday-strategy-audit"],
    queryFn: async () =>
      (
        await api.get(
          `/api/admin/market/backfill/strategy-readiness-audit?from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}&timeframe=1m&useCase=REPLAY`,
        )
      ).data?.data as StrategyAuditRow[],
    refetchInterval: 20000,
  });

  const setupState = useMemo(() => {
    const byKey = new Map((auditQ.data ?? []).map((r) => [r.strategyKey, r]));
    return INTRADAY_SETUPS.map((s) => {
      const a = byKey.get(s.strategyKey);
      return {
        ...s,
        ready: !!a?.ready,
        status: a?.status ?? "UNKNOWN",
        symbolsReady: a?.symbolsReady ?? 0,
        symbolsTotal: a?.symbolsTotal ?? 0,
        blockers: (a?.blockers ?? []).slice(0, 4),
      };
    });
  }, [auditQ.data]);

  const summary = useMemo(() => {
    const c = coverageQ.data ?? [];
    return {
      ready: c.filter((x) => x.replayReadiness === "READY" && x.scannerReadiness === "READY").length,
      stale: c.filter((x) => x.freshness === "STALE").length,
      gaps: c.reduce((n, x) => n + Math.max(0, x.gapCount || 0), 0),
      total: c.length,
    };
  }, [coverageQ.data]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className={isLight ? "text-2xl font-semibold text-neutral-900" : "text-2xl font-semibold text-white"}>Intraday Ops</h1>
          <p className={isLight ? "text-sm text-neutral-600" : "text-sm text-neutral-400"}>
            Dedicated admin module for intraday setup readiness, coverage, and operational recovery.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link to="/admin/backfill" className="rounded-lg border border-neutral-300 px-3 py-1.5 text-sm font-medium hover:bg-neutral-50 dark:border-neutral-700 dark:hover:bg-neutral-900">Open Backfill</Link>
          <Link to="/admin/broker-infrastructure?vendor=ZERODHA" className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700">Broker Session</Link>
        </div>
      </div>

      {(opsQ.isError || coverageQ.isError || auditQ.isError) ? (
        <div className="rounded-xl border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700">
          {parseAxiosMessage(opsQ.error ?? coverageQ.error ?? auditQ.error)}
        </div>
      ) : null}

      <div className="grid gap-4 md:grid-cols-4">
        <div className={isLight ? "rounded-xl border border-neutral-200 bg-white p-4" : "rounded-xl border border-neutral-800 bg-neutral-950 p-4"}>
          <p className="text-xs uppercase tracking-wider text-neutral-500">Symbols ready</p>
          <p className="mt-2 text-2xl font-semibold">{summary.ready}</p>
        </div>
        <div className={isLight ? "rounded-xl border border-neutral-200 bg-white p-4" : "rounded-xl border border-neutral-800 bg-neutral-950 p-4"}>
          <p className="text-xs uppercase tracking-wider text-neutral-500">Stale</p>
          <p className="mt-2 text-2xl font-semibold">{summary.stale}</p>
        </div>
        <div className={isLight ? "rounded-xl border border-neutral-200 bg-white p-4" : "rounded-xl border border-neutral-800 bg-neutral-950 p-4"}>
          <p className="text-xs uppercase tracking-wider text-neutral-500">Gaps</p>
          <p className="mt-2 text-2xl font-semibold">{summary.gaps}</p>
        </div>
        <div className={isLight ? "rounded-xl border border-neutral-200 bg-white p-4" : "rounded-xl border border-neutral-800 bg-neutral-950 p-4"}>
          <p className="text-xs uppercase tracking-wider text-neutral-500">Coverage rows</p>
          <p className="mt-2 text-2xl font-semibold">{summary.total}</p>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        {setupState.map((s) => (
          <div key={s.key} className={isLight ? "rounded-2xl border border-neutral-200 bg-white p-4" : "rounded-2xl border border-neutral-800 bg-neutral-950 p-4"}>
            <div className="flex items-center justify-between gap-3">
              <p className="font-semibold">{s.title}</p>
              <span className={s.ready ? "rounded-full border border-emerald-500 px-2 py-0.5 text-xs text-emerald-600" : "rounded-full border border-amber-500 px-2 py-0.5 text-xs text-amber-600"}>{s.status}</span>
            </div>
            <p className={isLight ? "mt-1 text-xs text-neutral-600" : "mt-1 text-xs text-neutral-400"}>
              Strategy: <span className="font-mono">{s.strategyKey}</span> • Ready {s.symbolsReady}/{s.symbolsTotal}
            </p>
            {s.blockers.length > 0 ? (
              <div className={isLight ? "mt-3 rounded-lg border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800" : "mt-3 rounded-lg border border-amber-800/40 bg-amber-950/20 p-2 text-xs text-amber-300"}>
                <p className="font-semibold">Top blockers</p>
                <ul className="mt-1 space-y-1">
                  {s.blockers.map((b) => (
                    <li key={`${b.symbol}-${b.state}`}>{b.symbol}: {b.state}</li>
                  ))}
                </ul>
              </div>
            ) : null}
            <div className="mt-3 flex flex-wrap gap-2">
              <Link to={`/backtests/launch?strategyKey=${encodeURIComponent(s.strategyKey)}`} className="rounded-lg border border-neutral-300 px-2 py-1 text-xs font-semibold hover:bg-neutral-50 dark:border-neutral-700 dark:hover:bg-neutral-900">
                Replay check
              </Link>
              <Link to="/admin/backfill" className="rounded-lg border border-blue-300 px-2 py-1 text-xs font-semibold text-blue-700 hover:bg-blue-50 dark:border-blue-700 dark:text-blue-300 dark:hover:bg-blue-950/20">
                Backfill / repair
              </Link>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
