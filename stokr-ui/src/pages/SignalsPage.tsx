import { useMutation, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { DataGrid } from "../components/data/DataGrid";
import { useUiThemeStore } from "../state/uiTheme";

type SignalRow = {
  id: string;
  createdAt: string | null;
  symbol: string | null;
  signalType: string | null;
  strategyName: string | null;
  reason: string | null;
  suggestedQty: string | null;
  confidenceScore: string | null;
};

type ReadinessIssue = {
  severity: "CRITICAL" | "WARNING" | "INFO";
  code: string;
  title: string;
  detail: string;
  action: string | null;
  strategyKey: string | null;
};

type IntradayReadiness = {
  overallStatus: "READY" | "WARNING" | "BLOCKED";
  blockers: ReadinessIssue[];
  warnings: ReadinessIssue[];
  info: ReadinessIssue[];
  severityCounters?: Record<string, number>;
};

const READINESS_ACTIONS = new Set(["RECONNECT_FEED", "RENEW_BROKER_SESSION", "RELOAD_INSTRUMENT_CACHE", "REFRESH_SUBSCRIPTIONS", "RESTART_RUNTIME"]);

function issueTone(issue?: ReadinessIssue): string {
  if (!issue) return "border-neutral-200 bg-neutral-50 text-neutral-700";
  if (issue.severity === "CRITICAL") return "border-rose-300 bg-rose-50 text-rose-800";
  if (issue.severity === "WARNING") return "border-amber-300 bg-amber-50 text-amber-800";
  return "border-blue-300 bg-blue-50 text-blue-800";
}

export function SignalsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const navigate = useNavigate();
  const [symbol, setSymbol] = useState("");

  const q = useQuery({
    queryKey: ["trader-signals-feed"],
    queryFn: async () => {
      const res = await api.get("/api/trader/strategy-feed?limit=500");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as SignalRow[];
    },
    refetchInterval: 15_000,
  });

  const readinessQ = useQuery({
    queryKey: ["intraday-readiness-signals"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as IntradayReadiness,
    refetchInterval: 10_000,
  });

  const readinessAction = useMutation({
    mutationFn: async (payload: { action: string; strategyKey?: string | null }) =>
      (await api.post("/api/trader/intraday/readiness/actions", payload)).data?.data,
    onSuccess: (data: { message?: string; payload?: { authorizeUrl?: string } }) => {
      if (data?.message) toast.success(data.message);
      const url = data?.payload?.authorizeUrl;
      if (url) window.open(url, "_blank", "noopener,noreferrer");
      void readinessQ.refetch();
      void q.refetch();
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const rows = useMemo(() => {
    const s = symbol.trim().toUpperCase();
    if (!s) return q.data ?? [];
    return (q.data ?? []).filter((r) => String(r.symbol ?? "").toUpperCase().includes(s));
  }, [q.data, symbol]);

  const cols = useMemo<ColumnDef<SignalRow>[]>(
    () => [
      { accessorKey: "createdAt", header: "Time" },
      { accessorKey: "strategyName", header: "Strategy" },
      { accessorKey: "symbol", header: "Symbol" },
      { accessorKey: "signalType", header: "Side" },
      { accessorKey: "suggestedQty", header: "Qty" },
      { accessorKey: "confidenceScore", header: "Confidence" },
      { accessorKey: "reason", header: "Rationale" },
    ],
    [],
  );

  const topIssue = useMemo(() => {
    const rd = readinessQ.data;
    if (!rd) return undefined;
    return rd.blockers[0] ?? rd.warnings[0] ?? rd.info[0];
  }, [readinessQ.data]);

  const canShowDiagnostic = !q.isLoading && rows.length === 0;

  async function runIssueAction(issue?: ReadinessIssue) {
    if (!issue?.action) return;
    const actionUpper = issue.action.toUpperCase();
    if (!READINESS_ACTIONS.has(actionUpper)) return;
    readinessAction.mutate({ action: actionUpper, strategyKey: issue.strategyKey });
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>
            Signals
          </h1>
          <p className={isLight ? "mt-1 text-sm text-neutral-600" : "mt-1 text-sm text-neutral-400"}>
            Live and historical trader signals by strategy and symbol.
          </p>
        </div>
        <input
          placeholder="Filter symbol"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          className={isLight ? "rounded-lg border border-neutral-200 bg-white px-3 py-1.5 text-sm text-neutral-900 outline-none focus:border-blue-500" : "rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-1.5 text-sm text-white outline-none focus:border-blue-600"}
        />
      </div>
      {canShowDiagnostic ? (
        <div className={`rounded-xl border p-4 ${issueTone(topIssue)}`}>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="text-sm font-semibold">Signals are empty because readiness is blocked</div>
              <div className="mt-1 text-xs">
                {topIssue ? `${topIssue.code}: ${topIssue.title}` : "No signal activity detected yet. Check Intraday readiness and runtime status."}
              </div>
              {topIssue?.detail ? <div className="mt-1 text-xs opacity-90">{topIssue.detail}</div> : null}
              <div className="mt-2 text-[11px] opacity-90">
                Critical {readinessQ.data?.severityCounters?.CRITICAL ?? 0} • Warnings {readinessQ.data?.severityCounters?.WARNING ?? 0}
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => navigate("/intraday")}
                className="rounded-md border border-neutral-300 bg-white px-2.5 py-1.5 text-xs font-semibold text-neutral-800 hover:bg-neutral-100"
              >
                Open Intraday
              </button>
              <button
                type="button"
                onClick={() => navigate("/brokers")}
                className="rounded-md border border-neutral-300 bg-white px-2.5 py-1.5 text-xs font-semibold text-neutral-800 hover:bg-neutral-100"
              >
                Re-auth Broker
              </button>
              <button
                type="button"
                onClick={() => navigate("/admin/backfill")}
                className="rounded-md border border-neutral-300 bg-white px-2.5 py-1.5 text-xs font-semibold text-neutral-800 hover:bg-neutral-100"
              >
                Open Backfill
              </button>
              {topIssue?.action && READINESS_ACTIONS.has(topIssue.action.toUpperCase()) ? (
                <button
                  type="button"
                  disabled={readinessAction.isPending}
                  onClick={() => void runIssueAction(topIssue)}
                  className="rounded-md border border-blue-300 bg-blue-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {topIssue.action.replaceAll("_", " ")}
                </button>
              ) : null}
            </div>
          </div>
        </div>
      ) : null}
      <DataGrid columns={cols} data={rows} isLoading={q.isLoading} emptyLabel="No live/history signals found yet" variant={isLight ? "light" : "dark"} />
    </div>
  );
}
