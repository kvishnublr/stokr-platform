import { TrendingDown, TrendingUp } from "lucide-react";
import { GlassPanel } from "./GlassPanel";
import { cn } from "../../lib/utils";

export function PositionCard({
  symbol,
  quantity,
  notional,
  pnlTone,
}: {
  symbol: string;
  quantity: string;
  notional: string;
  pnlTone?: "up" | "down";
}) {
  const Icon = pnlTone === "down" ? TrendingDown : TrendingUp;
  return (
    <GlassPanel
      className={cn(
        "flex items-center justify-between gap-4 p-4",
        pnlTone === "up" && "ring-1 ring-emerald-500/15",
        pnlTone === "down" && "ring-1 ring-rose-500/15",
      )}
    >
      <div>
        <div className="font-mono text-sm font-semibold text-white">{symbol}</div>
        <div className="mt-1 text-[11px] text-neutral-500">
          Qty <span className="font-mono text-neutral-300">{quantity}</span>
        </div>
      </div>
      <div className="text-right">
        <div className="font-mono text-xs text-neutral-300">{notional}</div>
        {pnlTone ? (
          <div
            className={cn(
              "mt-1 inline-flex items-center gap-1 text-[11px] font-medium",
              pnlTone === "up" ? "text-emerald-400" : "text-rose-400",
            )}
          >
            <Icon className="h-3 w-3" />
            Exposure snapshot
          </div>
        ) : (
          <div className="mt-1 text-[11px] text-neutral-600">Neutral</div>
        )}
      </div>
    </GlassPanel>
  );
}
