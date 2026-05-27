import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../lib/utils";

type GlassPanelVariant = "dark" | "light";

type GlassPanelProps = {
  children: ReactNode;
  className?: string;
  accent?: boolean;
  variant?: GlassPanelVariant;
  /** Subtle lift + shadow on hover (dense dashboards) */
  interactive?: boolean;
  /** Mount fade-in + optional hover lift via framer-motion */
  animated?: boolean;
};

export function GlassPanel({
  children,
  className,
  accent,
  variant = "dark",
  interactive,
  animated,
}: GlassPanelProps) {
  const classes = cn(
    variant === "dark" &&
      "rounded-2xl border border-neutral-800/80 bg-neutral-950/50 shadow-[0_0_0_1px_rgba(255,255,255,0.02)_inset] backdrop-blur-md",
    variant === "light" &&
      "rounded-xl border border-neutral-200/80 bg-white/95 shadow-[0_1px_0_0_rgba(255,255,255,0.8)_inset,0_4px_20px_-4px_rgba(15,23,42,0.07)] backdrop-blur-[10px]",
    accent && variant === "dark" && "shadow-[var(--shadow-glow,0_0_80px_rgba(59,130,246,0.08))]",
    accent && variant === "light" && "shadow-[0_12px_40px_-8px_rgba(59,130,246,0.14)]",
    interactive &&
      variant === "light" &&
      "transition-[transform,box-shadow,border-color] duration-200 hover:-translate-y-0.5 hover:border-blue-200/80 hover:shadow-[0_12px_36px_-12px_rgba(59,130,246,0.18)]",
    interactive &&
      variant === "dark" &&
      "transition-[transform,box-shadow] duration-200 hover:-translate-y-0.5 hover:border-neutral-700 hover:shadow-[0_16px_48px_-16px_rgba(0,0,0,0.55)]",
    className,
  );

  if (!animated) {
    return <div className={classes}>{children}</div>;
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      whileHover={interactive ? { y: -2 } : undefined}
      className={classes}
    >
      {children}
    </motion.div>
  );
}
