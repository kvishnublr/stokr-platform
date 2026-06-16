import { useQuery } from "@tanstack/react-query";
import { fetchSimulationStatus } from "../../api/simulation";
import { cn } from "../../lib/utils";

/** Visible admin-wide banner when simulation runtime is enabled. */
export function SimulationRuntimeBanner({ isLight }: { isLight: boolean }) {
  const { data } = useQuery({
    queryKey: ["sim-runtime"],
    queryFn: fetchSimulationStatus,
    refetchInterval: 10_000,
  });

  if (!data?.enabled) {
    return null;
  }

  return (
    <div
      role="alert"
      className={cn(
        "mb-3 flex items-center justify-between gap-3 rounded-lg border px-4 py-2 text-sm font-medium",
        isLight
          ? "border-amber-400 bg-amber-50 text-amber-950"
          : "border-amber-500/60 bg-amber-950/80 text-amber-100",
      )}
    >
      <span>
        SIMULATION MODE ACTIVE — synthetic market &amp; simulated broker only. Live Zerodha/Fyers/Dhan orders are
        hard-blocked. Production PnL and effectiveness metrics are isolated.
      </span>
      {data.enabledAt && (
        <span className="shrink-0 text-xs opacity-80">since {data.enabledAt}</span>
      )}
    </div>
  );
}
