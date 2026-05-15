import type { ReactNode } from "react";
import { cn } from "../../lib/utils";

type Snap = Record<string, unknown>;

function asRec(v: unknown): Record<string, unknown> | undefined {
  return v != null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

/** Compact ops readout driven by `/api/admin/operations/snapshot` — no decorative charts. */
export function AdminOpsTelemetryGrid({ snapshot, isLight }: { snapshot: Snap; isLight: boolean }) {
  const mf = asRec(snapshot.marketFreshness);
  const bi = asRec(snapshot.brokerSessions);
  const vendors = bi?.vendors;
  const scan = asRec(snapshot.scannerTelemetry);
  const oms = asRec(snapshot.oms);
  const sig = asRec(snapshot.signalDistribution);
  const te = asRec(snapshot.traderExecutionHealth);
  const inc = Array.isArray(snapshot.incidents) ? (snapshot.incidents as Record<string, unknown>[]) : [];

  const panel = isLight ? "border-neutral-200 bg-neutral-50" : "border-white/[0.08] bg-neutral-950/60";

  function cell(title: string, body: ReactNode) {
    return (
      <div className={cn("rounded-lg border p-3 text-left", panel)}>
        <div className={cn("text-[10px] font-bold uppercase tracking-widest", isLight ? "text-neutral-500" : "text-neutral-500")}>
          {title}
        </div>
        <div className={cn("mt-2 font-mono text-[11px] leading-relaxed", isLight ? "text-neutral-900" : "text-neutral-200")}>{body}</div>
      </div>
    );
  }

  const vendorLines =
    vendors != null && typeof vendors === "object"
      ? Object.entries(vendors as Record<string, unknown>)
          .map(([k, v]) => {
            const vr = asRec(v);
            return `${k}: ${String(vr?.status ?? "?")} · rows ${String(vr?.accountRows ?? "?")}`;
          })
          .join("\n")
      : "—";

  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      {cell(
        "Market freshness",
        <>
          status {String(mf?.status ?? "—")}
          <br />
          lag1m {mf?.latest1mLagSeconds != null ? `${Number(mf.latest1mLagSeconds).toFixed(0)}s` : "—"}
        </>,
      )}
      {cell("Brokers (DB projection)", vendorLines)}
      {cell(
        "Scanner / signals",
        <>
          sig/60m {String(scan?.signalsEmittedLast60m ?? "—")}
          <br />
          running {String(scan?.runningStrategyInstances ?? "—")}
        </>,
      )}
      {cell(
        "OMS / execution",
        <>
          orders/s ~{oms?.ordersPerSecApprox != null ? Number(oms.ordersPerSecApprox).toFixed(3) : "—"}
          <br />
          stuck ~{String(oms?.stuckOrdersApprox ?? "—")}
          <br />
          sig total {String(sig?.signalsPersistedTotal ?? "—")}
        </>,
      )}
      {cell(
        "Trader health",
        <>
          live-approved {String(te?.liveTradingApprovedUsers ?? "—")}
          <br />
          broker-linked users {String(te?.distinctUsersBrokerConnected ?? "—")}
        </>,
      )}
      {cell(
        "Incidents (count)",
        <>
          {inc.length === 0 ? "none" : `${inc.length} open`}
          {inc.slice(0, 3).map((i) => (
            <div key={String(i.code)} className="mt-1 text-[10px] text-amber-200/90">
              {String(i.code)}: {String(i.title ?? "")}
            </div>
          ))}
        </>,
      )}
    </div>
  );
}
