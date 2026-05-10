import { motion } from "framer-motion";
import type { FeedEvent } from "../../state/notifications";
import { cn } from "../../lib/utils";

export function ActivityTimeline({
  items,
  className,
  maxVisible = 20,
}: {
  items: FeedEvent[];
  className?: string;
  maxVisible?: number;
}) {
  const list = items.slice(0, maxVisible);
  if (list.length === 0) {
    return <p className="text-xs text-neutral-600">Operational feed clears when you reconnect — standby for realtime.</p>;
  }
  return (
    <div className={cn("relative space-y-0", className)}>
      <div
        aria-hidden
        className="absolute bottom-2 left-[7px] top-2 w-px bg-gradient-to-b from-neutral-700 via-neutral-800 to-transparent"
      />
      {list.map((e, idx) => (
        <motion.div
          layout
          key={e.id}
          initial={{ opacity: 0, x: -4 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: idx * 0.02 }}
          className="relative flex gap-3 py-2 pl-5"
        >
          <span
            className={cn(
              "absolute left-1 top-[11px] h-2 w-2 rounded-full ring-2 ring-neutral-950",
              e.severity === "success" && "bg-emerald-400",
              e.severity === "error" && "bg-rose-400",
              e.severity === "warning" && "bg-amber-400",
              e.severity === "info" && "bg-blue-400",
            )}
          />
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-medium text-neutral-100">{e.title}</div>
            {e.detail ? <div className="truncate text-[11px] text-neutral-500">{e.detail}</div> : null}
            <div className="mt-1 text-[10px] font-mono text-neutral-600">
              {new Date(e.ts).toLocaleTimeString(undefined, {
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
              })}
            </div>
          </div>
        </motion.div>
      ))}
    </div>
  );
}
