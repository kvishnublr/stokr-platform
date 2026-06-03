import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Ghost, ShieldAlert, Trash2 } from "lucide-react";
import { toast } from "sonner";
import {
  clearGhostPositions,
  type PositionReconciliationDiagnostics,
  type PositionReconciliationRow,
} from "../../api/positionReconciliation";
import { parseAxiosMessage } from "../../api/client";
import { fmtDateTime } from "../../lib/dateUtils";
import { cn } from "../../lib/utils";
import { toneChipClasses } from "../../lib/statusTone";
import {
  AdminPanel,
  AdminSection,
} from "./institutional/AdminDesignSystem";

function fmtVal(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "boolean") return v ? "YES" : "NO";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "—";
  if (typeof v === "string") return v;
  return JSON.stringify(v);
}

function statusTone(status: string | undefined): "success" | "warn" | "critical" | "neutral" {
  const s = (status ?? "OK").toUpperCase();
  if (s.includes("GHOST") && s.includes("BLOCKING")) return "critical";
  if (s.includes("GHOST")) return "warn";
  if (s.includes("BLOCKING")) return "critical";
  return "success";
}

function MetricGrid({
  items,
  isLight,
}: {
  items: Array<{ label: string; value: unknown; warn?: boolean }>;
  isLight: boolean;
}) {
  return (
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
      {items.map((item) => (
        <div
          key={item.label}
          className={cn(
            "rounded-lg border px-3 py-2",
            item.warn
              ? isLight
                ? "border-rose-300 bg-rose-50"
                : "border-rose-500/40 bg-rose-500/10"
              : isLight
                ? "border-neutral-200 bg-neutral-50"
                : "border-neutral-800 bg-neutral-900/50",
          )}
        >
          <p className={cn("text-[10px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>
            {item.label}
          </p>
          <p className={cn("mt-1 font-mono text-xs font-semibold", item.warn ? "text-rose-500" : isLight ? "text-neutral-900" : "text-neutral-100")}>
            {fmtVal(item.value)}
          </p>
        </div>
      ))}
    </div>
  );
}

export function PositionReconciliationPanel({
  data,
  isLight,
  queryKey,
}: {
  data: PositionReconciliationDiagnostics;
  isLight: boolean;
  queryKey: readonly string[];
}) {
  const qc = useQueryClient();
  const summary = data.summary ?? {};
  const rows = data.rows ?? [];

  const clearMut = useMutation({
    mutationFn: clearGhostPositions,
    onSuccess: (res) => {
      toast.success(`Cleared ${res.clearedGhosts ?? 0} ghost position(s)`);
      qc.setQueryData(queryKey, res);
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  return (
    <AdminSection
      isLight={isLight}
      title="Position reconciliation"
      subtitle="OMS open legs vs broker holdings — ghosts and max-position blocks without SQL"
    >
      <div className="space-y-4">
        <MetricGrid
          isLight={isLight}
          items={[
            { label: "Open OMS legs", value: summary.totalOpen },
            { label: "Ghost", value: summary.ghostCount, warn: (summary.ghostCount ?? 0) > 0 },
            { label: "Blocking LIVE", value: summary.blockingCount, warn: (summary.blockingCount ?? 0) > 0 },
            { label: "Strategies at capacity", value: summary.strategiesAtCapacity, warn: (summary.strategiesAtCapacity ?? 0) > 0 },
            { label: "Broker connected", value: summary.brokerConnected },
            { label: "Broker sync", value: summary.brokerSyncState },
            { label: "Stale threshold (h)", value: summary.staleThresholdHours },
            { label: "Primary trader", value: summary.primaryTraderUserId },
          ]}
        />

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={clearMut.isPending || (summary.ghostCount ?? 0) === 0}
            onClick={() => clearMut.mutate()}
            className="inline-flex items-center gap-2 rounded-lg border border-amber-400 bg-amber-50 px-3 py-2 text-xs font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-50 dark:border-amber-600 dark:bg-amber-500/10 dark:text-amber-100"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Clear zero-price ghosts
          </button>
          {(summary.ghostCount ?? 0) > 0 ? (
            <p className={cn("text-xs", isLight ? "text-amber-800" : "text-amber-200")}>
              <Ghost className="mr-1 inline h-3.5 w-3.5" />
              Ghost rows inflate open-position counts and can block LIVE entries.
            </p>
          ) : null}
        </div>

        <AdminPanel isLight={isLight} title="Open positions" accent={(summary.ghostCount ?? 0) > 0 || (summary.blockingCount ?? 0) > 0}>
          {rows.length === 0 ? (
            <p className="text-xs text-muted-foreground">No open real portfolio legs.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
                  <tr>
                    {["Strategy", "Symbol", "Qty", "Avg", "User", "Broker", "Signal", "Status"].map((h) => (
                      <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <ReconciliationRow key={String(row.positionId)} row={row} isLight={isLight} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </AdminPanel>
      </div>
    </AdminSection>
  );
}

function ReconciliationRow({ row, isLight }: { row: PositionReconciliationRow; isLight: boolean }) {
  const sig = row.signal;
  const brokerLabel = row.brokerConnected === false
    ? "—"
    : row.brokerMatch
      ? `✓ ${fmtVal(row.brokerQty)}`
      : `${fmtVal(row.brokerQty)} ≠ OMS`;

  return (
    <tr className="border-t dark:border-neutral-800">
      <td className="px-2 py-2 font-medium">{fmtVal(row.strategyKey)}</td>
      <td className="px-2 py-2">{fmtVal(row.symbol)}</td>
      <td className="px-2 py-2 font-mono">{fmtVal(row.quantity)}</td>
      <td className="px-2 py-2 font-mono">{fmtVal(row.avgPrice)}</td>
      <td className="px-2 py-2" title={row.userId}>
        {row.userEmail ?? fmtVal(row.userId)}
      </td>
      <td className={cn("px-2 py-2 font-mono", row.brokerMatch === false ? "text-rose-500" : "")}>
        {brokerLabel}
      </td>
      <td className="px-2 py-2">
        {sig ? (
          <span title={sig.id}>
            {fmtVal(sig.outcomeStatus)}
            {sig.signalType ? ` · ${sig.signalType}` : ""}
          </span>
        ) : (
          "—"
        )}
      </td>
      <td className="px-2 py-2">
        <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase", toneChipClasses(isLight, statusTone(row.status)))}>
          {row.status ?? "OK"}
        </span>
        {row.strategyOpenCount != null && row.maxPositionsForStrategy != null ? (
          <p className="mt-0.5 text-[9px] text-muted-foreground">
            slots {row.strategyOpenCount}/{row.maxPositionsForStrategy}
          </p>
        ) : null}
        {row.ghostReasons ? (
          <p className="mt-0.5 text-[9px] text-amber-600">
            {[
              row.ghostReasons.zeroPrice ? "zero price" : null,
              row.ghostReasons.stale ? "stale" : null,
              row.ghostReasons.brokerFlat ? "broker flat" : null,
            ].filter(Boolean).join(", ")}
          </p>
        ) : null}
      </td>
    </tr>
  );
}

export function PositionReconciliationLoadError({
  error,
  onRetry,
  isLight,
}: {
  error: unknown;
  onRetry: () => void;
  isLight: boolean;
}) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3 text-sm",
        isLight ? "border-amber-300 bg-amber-50 text-amber-900" : "border-amber-500/40 bg-amber-500/10 text-amber-100",
      )}
    >
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0" />
        <span>Position reconciliation: {parseAxiosMessage(error)}</span>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className={cn("rounded-md border px-2 py-1 text-xs font-bold", isLight ? "border-amber-400" : "border-amber-600")}
      >
        Retry
      </button>
    </div>
  );
}

export function PositionReconciliationCompactBanner({
  data,
  isLight,
  to,
}: {
  data: PositionReconciliationDiagnostics | undefined;
  isLight: boolean;
  to: string;
}) {
  const ghost = data?.summary?.ghostCount ?? 0;
  const blocking = data?.summary?.blockingCount ?? 0;
  if (ghost === 0 && blocking === 0) return null;

  return (
    <div
      className={cn(
        "mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3 text-sm",
        isLight ? "border-rose-300 bg-rose-50 text-rose-900" : "border-rose-500/40 bg-rose-500/10 text-rose-100",
      )}
    >
      <div className="flex items-center gap-2">
        <ShieldAlert className="h-4 w-4 shrink-0" />
        <span>
          {ghost > 0 ? `${ghost} ghost position(s)` : null}
          {ghost > 0 && blocking > 0 ? " · " : null}
          {blocking > 0 ? `${blocking} blocking max-position slot(s)` : null}
        </span>
      </div>
      <a href={to} className="text-xs font-bold underline">
        View reconciliation
      </a>
    </div>
  );
}
