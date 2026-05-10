import type { ReactNode } from "react";
import { cn } from "../../lib/utils";

export type TabItem = { id: string; label: string; badge?: string };

export function WorkspaceTabs({
  tabs,
  active,
  onChange,
}: {
  tabs: TabItem[];
  active: string;
  onChange: (id: string) => void;
}) {
  return (
    <div
      role="tablist"
      aria-label="Workspace"
      className="flex flex-wrap gap-1 rounded-xl border border-neutral-800/80 bg-neutral-950/60 p-1 backdrop-blur"
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
              "relative flex items-center gap-2 rounded-lg px-3 py-2 text-xs font-semibold transition",
              selected
                ? "bg-neutral-800 text-white shadow-sm ring-1 ring-white/10"
                : "text-neutral-500 hover:bg-neutral-900 hover:text-neutral-200",
            )}
          >
            {t.label}
            {t.badge ? (
              <span className="rounded-md bg-blue-500/20 px-1.5 py-0.5 text-[10px] font-bold text-blue-200">
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
    <div role="tabpanel" className="mt-4">
      {children}
    </div>
  );
}
