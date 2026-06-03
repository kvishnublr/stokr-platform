import { adminOpsMetricsStrip, buildAdminOpsPills } from "./adminOpsModel";
import { badgeClassForStatus, type OpsSnapshot } from "./cockpit/opsTypes";

/**
 * Persistent institutional ops header - all admin routes (below workspace topnav).
 */
export function AdminGlobalOpsHeader({
  snapshot,
  isFetching,
  snapshotLoading,
  snapshotFetching,
  opsStreamLive,
  lastOpsPushAt,
  streamError,
}: {
  snapshot: OpsSnapshot | undefined;
  isFetching: boolean;
  snapshotLoading?: boolean;
  snapshotFetching?: boolean;
  opsStreamLive: boolean;
  lastOpsPushAt?: string;
  streamError?: string;
}) {
  const pills = buildAdminOpsPills(snapshot, {
    opsStreamLive,
    lastOpsPushAt,
    streamError,
    snapshotLoading: snapshotLoading === true,
    snapshotFetching: snapshotFetching === true,
  });
  const at = snapshot?.collectedAt ?? "";

  return (
    <div
      className="sticky top-0 z-[15] -mx-4 border-b-2 border-border bg-background px-3 py-2.5 shadow-md backdrop-blur supports-[backdrop-filter]:bg-background/90 sm:-mx-6 lg:-mx-10"
      role="region"
      aria-label="Global operational status"
    >
      <div className="flex flex-wrap items-end gap-2">
        <div className="mr-1 min-w-[8.5rem] pb-1 text-[10px] font-bold uppercase tracking-wide text-foreground">
          Readiness strip
          {opsStreamLive ? <span className="ml-1 font-mono font-semibold text-emerald-600 dark:text-emerald-400"> ·  SSE</span> : null}
          {!opsStreamLive && isFetching ? <span className="ml-1 font-mono text-muted-foreground"> ·  sync</span> : null}
        </div>
        {pills.map((p) => (
          <div
            key={p.key}
            className={`flex min-h-[3.25rem] min-w-0 max-w-[11rem] flex-col justify-center rounded-md border-2 px-2 py-1 ${badgeClassForStatus(p.status)}`}
            title={p.hint ? `${p.label}: ${p.hint}` : p.label}
          >
            <span className="truncate text-[9px] font-bold uppercase tracking-wide text-foreground/80">{p.label}</span>
            <span className="truncate font-mono text-[11px] font-bold leading-tight text-foreground">{p.status.replace(/_/g, " ")}</span>
            {p.hint ? (
              <span className="line-clamp-2 truncate text-[9px] leading-tight text-muted-foreground">{p.hint}</span>
            ) : null}
          </div>
        ))}
        <div className="ml-auto hidden pb-1 text-right font-mono text-[10px] text-muted-foreground min-[1100px]:block">
          <div className="font-bold uppercase tracking-wide text-foreground/80">Snapshot wall</div>
          <div className="text-foreground">{at ? new Date(at).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "-"}</div>
        </div>
      </div>
      <div className="mt-2 truncate border-t border-border pt-1.5 font-mono text-[10px] text-foreground" title={adminOpsMetricsStrip(snapshot)}>
        {adminOpsMetricsStrip(snapshot)}
      </div>
    </div>
  );
}
