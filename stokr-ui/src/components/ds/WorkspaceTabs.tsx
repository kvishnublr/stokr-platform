import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../lib/utils";

export type TabItem = { id: string; label: string; badge?: string };

export function WorkspaceTabs({
  tabs,
  active,
  onChange,
  variant = "dark",
}: {
  tabs: TabItem[];
  active: string;
  onChange: (id: string) => void;
  variant?: "dark" | "light";
}) {
  const light = variant === "light";
  return (
    <div
      role="tablist"
      aria-label="Workspace"
      className={cn(
        "relative flex flex-wrap gap-1 rounded-xl border p-1 backdrop-blur",
        light
          ? "border-neutral-200 bg-white/90 shadow-sm"
          : "border-neutral-800/80 bg-neutral-950/60",
      )}
    >
      {tabs.map((t) => {
        const selected = t.id === active;
        return (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={selected}
            onClick={() => onChange(t.id)}
            className={cn(
              "relative z-[1] flex items-center gap-2 rounded-lg px-3 py-2 text-xs font-semibold transition-colors duration-200",
              light
                ? selected
                  ? "text-white"
                  : "text-neutral-600 hover:text-neutral-900"
                : selected
                  ? "text-white"
                  : "text-neutral-500 hover:text-neutral-200",
            )}
          >
            {selected ? (
              <motion.span
                layoutId="workspace-tab-pill"
                className={cn(
                  "absolute inset-0 rounded-lg shadow-sm",
                  light ? "bg-neutral-900" : "bg-neutral-800 ring-1 ring-white/10",
                )}
                transition={{ type: "spring", stiffness: 420, damping: 32 }}
              />
            ) : null}
            <span className="relative">{t.label}</span>
            {t.badge ? (
              <span
                className={cn(
                  "relative rounded-md px-1.5 py-0.5 text-[10px] font-bold",
                  light ? "bg-blue-100 text-blue-800" : "bg-blue-500/20 text-blue-200",
                )}
              >
                {t.badge}
              </span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

export function WorkspaceTabPanel({ id, active, children }: { id: string; active: string; children: ReactNode }) {
  if (id !== active) return null;
  return (
    <motion.div
      role="tabpanel"
      key={id}
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
      className="mt-4"
    >
      {children}
    </motion.div>
  );
}
