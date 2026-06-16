import { Link } from "react-router-dom";
import { asRecord, badgeClassForStatus, fmtInt, type OpsSnapshot } from "./cockpit/opsTypes";

/**
 * Institutional-first panel: admin platform market tape (Zerodha) - precedes trader broker_accounts cards.
 */
export function PlatformLiveInfrastructurePanel({ snapshot }: { snapshot: OpsSnapshot | undefined }) {
  const life = asRecord(snapshot?.operationalLifecycle);
  const root = asRecord(snapshot?.platformMarketFeed);
  const z = asRecord(asRecord(root?.vendors)?.ZERODHA) ?? {};
  const infra = asRecord(snapshot?.marketInfra);

  const tapeState = String(life?.platformTapeState ?? z.connectionState ?? "OFFLINE").toUpperCase();
  const pathOk = life?.livePathOperational === true;
  const headline = life?.headline != null ? String(life.headline) : null;
  const reason = life?.platformTapeReason != null ? String(life.platformTapeReason) : String(z.operationalLivePathDetail ?? "");
  const scannerRaw = String(life?.scannerEngineState ?? (pathOk ? "IDLE" : "PAUSED")).toUpperCase();
  const scannerSt = scannerRaw === "PAUSED" && pathOk ? "IDLE" : scannerRaw;
  const sigRaw = String(life?.signalGenerationState ?? "UNAVAILABLE").toUpperCase();
  const sigSt = sigRaw === "PAUSED" && pathOk ? "IDLE" : sigRaw;
  const omsSt = String(life?.omsPlaneState ?? "UNKNOWN").toUpperCase();
  const replaySt = String(life?.replayCouplingState ?? "UNKNOWN").toUpperCase();
  const blocked = Array.isArray(life?.blockedDependencySummary)
    ? (life?.blockedDependencySummary as unknown[]).map((x) => String(x))
    : [];

  const ticks60 = infra?.ticksIngestedLast60sPlatformWs;
  const pps = z.packetsPerSec;
  const hb = z.heartbeatAgeSeconds ?? z.latestTickAgeSeconds;

  const borderClass = pathOk
    ? "border-emerald-600/45 bg-emerald-600/10"
    : tapeState === "RECONNECTING" || tapeState === "DEGRADED"
      ? "border-amber-500/55 bg-amber-500/12"
      : "border-red-600/55 bg-red-600/12";

  return (
    <section className={`rounded-xl border-2 bg-card px-4 py-4 text-foreground shadow-sm ${borderClass}`}>
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Platform live market infrastructure</div>
          <h2 className="mt-1 text-lg font-bold tracking-tight">Centralized market tape  ·  Zerodha</h2>
          {headline ? <p className="mt-2 max-w-4xl text-sm leading-relaxed text-foreground">{headline}</p> : null}
          {!pathOk && blocked.length > 0 ? (
            <div className="mt-3 rounded-lg border border-border bg-background/80 px-3 py-2">
              <div className="text-[10px] font-bold uppercase tracking-wide text-muted-foreground">Unavailable until restored</div>
              <ul className="mt-1 list-inside list-disc text-sm text-foreground">
                {blocked.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            </div>
          ) : null}
          {reason && !pathOk ? (
            <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
              <span className="font-semibold text-foreground">Reason: </span>
              {reason}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 flex-col gap-2 sm:flex-row sm:items-center">
          <span className={`rounded-md border px-2.5 py-1 text-center font-mono text-[11px] font-bold ${badgeClassForStatus(tapeState)}`}>
            {tapeState.replace(/_/g, " ")}
          </span>
          <Link
            to="/admin/broker-infrastructure?vendor=ZERODHA"
            className="rounded-lg border border-primary/40 bg-primary/10 px-3 py-2 text-center text-xs font-bold text-foreground hover:bg-primary/15"
          >
            Connect broker
          </Link>
          <Link
            to="/admin/market-intelligence"
            className="rounded-lg border border-border bg-background px-3 py-2 text-center text-xs font-semibold text-foreground hover:bg-muted"
          >
            Market intelligence
          </Link>
        </div>
      </div>

      <div className="mt-4 grid gap-3 border-t border-border pt-4 sm:grid-cols-2 xl:grid-cols-4">
        <dl className="space-y-1 rounded-lg border border-border bg-background/60 px-3 py-2 font-mono text-[11px]">
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Websocket</dt>
            <dd className={`font-bold ${badgeClassForStatus(String(z.websocketState ?? "CLOSED").toUpperCase())}`}>
              {String(z.websocketState ?? "CLOSED")}
            </dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Packets/sec</dt>
            <dd className="text-foreground">{typeof pps === "number" && Number.isFinite(pps) ? pps.toFixed(2) : "-"}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Tick / HB age</dt>
            <dd className="text-foreground">{hb != null ? `${fmtInt(hb)}s` : "-"}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Ticks (60s)</dt>
            <dd className="text-foreground">{fmtInt(ticks60)}</dd>
          </div>
        </dl>
        <dl className="space-y-1 rounded-lg border border-border bg-background/60 px-3 py-2 font-mono text-[11px]">
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Scanner</dt>
            <dd className={`font-bold ${badgeClassForStatus(scannerSt)}`}>{scannerSt}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Live signals</dt>
            <dd className={`font-bold ${badgeClassForStatus(sigSt)}`}>{sigSt}</dd>
          </div>
          <div className="text-[10px] leading-snug text-muted-foreground">
            {life?.scannerPollSkipped === true && life?.scannerPollSkipReason
              ? `Last poll: skipped - ${String(life.scannerPollSkipReason)}`
              : "Latest scheduler cycle status."}
          </div>
        </dl>
        <dl className="space-y-1 rounded-lg border border-border bg-background/60 px-3 py-2 font-mono text-[11px]">
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>OMS plane</dt>
            <dd className={`font-bold ${badgeClassForStatus(omsSt)}`}>{omsSt}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Replay coupling</dt>
            <dd className={`font-bold ${badgeClassForStatus(replaySt === "OK" ? "CONNECTED" : "STALE")}`}>{replaySt}</dd>
          </div>
          <div className="text-[10px] leading-snug text-muted-foreground">
            {life?.replayCouplingDetail ? String(life.replayCouplingDetail) : "-"}
          </div>
        </dl>
        <dl className="space-y-1 rounded-lg border border-border bg-background/60 px-3 py-2 font-mono text-[11px]">
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Subscriptions</dt>
            <dd className="text-foreground">{fmtInt(z.subscriptionCount)}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Reconnects</dt>
            <dd className="text-foreground">{fmtInt(z.reconnectCount)}</dd>
          </div>
          <div className="flex justify-between gap-2 text-muted-foreground">
            <dt>Symbols (telemetry)</dt>
            <dd className="truncate text-right text-foreground" title={String(z.streamingSymbols ?? "")}>
              {z.streamingSymbols != null ? String(z.streamingSymbols) : "-"}
            </dd>
          </div>
        </dl>
      </div>
    </section>
  );
}


