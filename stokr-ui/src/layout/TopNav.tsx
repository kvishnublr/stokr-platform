import { motion } from "framer-motion";
import { Wifi, Zap } from "lucide-react";
import type { ReactNode } from "react";
import { StatusChip } from "../components/ds/StatusChip";
import { useSessionStore } from "../state/session";

export function TopNav({
  displayNameFallback,
  right,
}: {
  displayNameFallback?: string;
  right: ReactNode;
}) {
  const displayName = useSessionStore((s) => s.displayName);
  const username = useSessionStore((s) => s.username);

  return (
    <div className="flex flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-6 lg:px-8">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <StatusChip status="online" label="WS feed" />
          <span className="hidden items-center gap-1 rounded-lg border border-neutral-800 px-2 py-0.5 text-[10px] font-medium text-neutral-400 sm:inline-flex">
            <Zap className="h-3 w-3 text-amber-400" /> Low-latency path
          </span>
          <span className="hidden items-center gap-1 rounded-lg border border-neutral-800 px-2 py-0.5 text-[10px] text-neutral-500 sm:inline-flex">
            <Wifi className="h-3 w-3" /> Replay-safe lineage
          </span>
        </div>
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="mt-1 text-sm">
          <span className="text-neutral-500">Signed in  ·  </span>
          <span className="truncate font-semibold text-white">{displayName || username || displayNameFallback}</span>
        </motion.div>
      </div>

      <div className="flex flex-wrap items-center gap-2">{right}</div>
    </div>
  );
}
