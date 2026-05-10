import type { ReactNode } from "react";
import { cn } from "../../lib/utils";

type GlassPanelProps = {
  children: ReactNode;
  className?: string;
  accent?: boolean;
};

export function GlassPanel({ children, className, accent }: GlassPanelProps) {
  return (
    <div
      className={cn(
        "rounded-2xl border border-neutral-800/80 bg-neutral-950/50 shadow-[0_0_0_1px_rgba(255,255,255,0.02)_inset] backdrop-blur-md",
        accent && "shadow-[var(--shadow-glow,0_0_80px_rgba(59,130,246,0.08))]",
        className,
      )}
    >
      {children}
    </div>
  );
}
