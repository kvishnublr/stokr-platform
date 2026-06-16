import { Cpu } from "lucide-react";
import { GlassPanel } from "./GlassPanel";
import { StatusChip } from "./StatusChip";
import type { OperationalStatus } from "./StatusChip";

export function RuntimeCard({
  strategyName,
  instanceIdShort,
  state,
  mode,
}: {
  strategyName: string;
  instanceIdShort: string;
  state: string;
  mode: string;
}) {
  const heartbeatOk = state?.toUpperCase() === "RUNNING";
  const net: OperationalStatus = heartbeatOk ? "online" : "pending";
  return (
    <GlassPanel className="p-4">
      <div className="flex items-start gap-3">
        <div className="rounded-lg bg-violet-500/15 p-2 text-violet-300 ring-1 ring-violet-500/25">
          <Cpu className="h-4 w-4" />
        </div>
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate text-sm font-semibold text-white">{strategyName}</span>
            <StatusChip status={net} label={heartbeatOk ? "Heartbeat OK" : "Stopped / idle"} />
            <StatusChip
              status={mode?.toUpperCase() === "LIVE" ? "live" : "paper"}
              label={mode?.toUpperCase() === "LIVE" ? "LIVE mode" : "Paper / SIM"}
            />
          </div>
          <div className="font-mono text-[10px] text-neutral-600">Instance  ·  {instanceIdShort}</div>
          <div className="text-xs text-neutral-500">State machine: {state}</div>
        </div>
      </div>
    </GlassPanel>
  );
}
