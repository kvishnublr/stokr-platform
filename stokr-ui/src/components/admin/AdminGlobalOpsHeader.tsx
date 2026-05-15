import { adminOpsMetricsStrip, buildAdminOpsPills } from "./adminOpsModel";
import { badgeClassForStatus, type OpsSnapshot } from "./cockpit/opsTypes";

/**
 * Persistent institutional ops header — all admin routes (below workspace topnav).
 */
export function AdminGlobalOpsHeader({
  snapshot,
  isFetching,
  opsStreamLive,
  lastOpsPushAt,
  streamError,
}: {
  snapshot: OpsSnapshot | undefined;
  isFetching: boolean;
  opsStreamLive: boolean;
  lastOpsPushAt?: string;
  streamError?: string;
}) {
  const pills = buildAdminOpsPills(snapshot, { opsStreamLive, lastOpsPushAt, streamError });
  const at = snapshot?.collectedAt ?? "";

  return (
    <div
      className="sticky top-0 z-[15] -mx-4 border-b border-border bg-background/95 px-3 py-2 shadow-sm backdrop-blur supports-[backdrop-filter]:bg-background/85 sm:-mx-6 lg:-mx-10"
      role="region"
      aria-label="Global operational status"
    >
      <div className="flex flex-wrap items-center gap-1.5">
        <div className="mr-1 min-w-[7rem] text-[9px] font-bold uppercase tracking-wide text-muted-foreground">
          Ops plane
          {opsStreamLive ? <span className="ml-1 font-mono font-semibold text-emerald-600 dark:text-emerald-400">LIVE</span> : null}
          {!opsStreamLive && isFetching ? <span className="ml-1 text-foreground">SYNC</span> : null}
        </div>
        {pills.map((p) => (
          <div
            key={p.key}
            className={`flex min-w-0 max-w-[10.5rem] flex-col rounded border px-1.5 py-0.5 ${badgeClassForStatus(p.status)}`}
            title={p.hint ? `${p.label}: ${p.hint}` : p.label}
          >
            <span className="truncate text-[8px] font-semibold uppercase tracking-wide text-muted-foreground">{p.label}</span>
            <span className="truncate font-mono text-[10px] font-semibold leading-tight">{p.status}</span>
            {p.hint ? (
              <span className="line-clamp-2 truncate text-[8px] leading-tight text-muted-foreground">{p.hint}</span>
            ) : null}
          </div>
        ))}
        <div className="ml-auto hidden text-right font-mono text-[9px] text-muted-foreground min-[1100px]:block">
          <div>snapshot wall</div>
          <div className="text-foreground">{at ? new Date(at).toLocaleString() : "—"}</div>
        </div>
      </div>
      <div className="mt-1.5 truncate font-mono text-[9px] text-muted-foreground" title={adminOpsMetricsStrip(snapshot)}>
        {adminOpsMetricsStrip(snapshot)}
      </div>
    </div>
  );
}
