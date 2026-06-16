import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../lib/utils";

type GlassPanelVariant = "dark" | "light";

type GlassPanelProps = {
  children: ReactNode;
  className?: string;
  accent?: boolean;
  variant?: GlassPanelVariant;
  interactive?: boolean;
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
    "rounded-xl border backdrop-blur-lg transition-all duration-300",
    variant === "dark" &&
      "border-border/60 bg-surface-glass shadow-[0_0_0_1px_rgba(255,255,255,0.02)_inset]",
    variant === "light" &&
      "border-neutral-200/80 bg-white/95 shadow-[0_1px_0_0_rgba(255,255,255,0.8)_inset,0_4px_20px_-4px_rgba(15,23,42,0.07)] backdrop-blur-[10px]",
    accent && variant === "dark" && "shadow-[var(--shadow-glow)]",
    accent && variant === "light" && "shadow-[0_12px_40px_-8px_rgba(59,130,246,0.14)]",
    interactive &&
      variant === "dark" &&
      "hover:-translate-y-0.5 hover:border-edge-strong/60 hover:shadow-[0_16px_48px_-16px_rgba(0,0,0,0.55)]",
    interactive &&
      variant === "light" &&
      "hover:-translate-y-0.5 hover:border-blue-200/80 hover:shadow-[0_12px_36px_-12px_rgba(59,130,246,0.18)]",
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
