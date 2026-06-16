import type { ComponentType, ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../lib/utils";
import { fadeUp, staggerContainer } from "../../lib/motionPresets";
import { GlassPanel } from "./GlassPanel";

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  variant = "dark",
}: {
  icon?: ComponentType<{ className?: string; "aria-hidden"?: boolean }>;
  title: string;
  description?: string;
  action?: ReactNode;
  variant?: "dark" | "light";
}) {
  const isLight = variant === "light";

  return (
    <GlassPanel variant={variant} className="overflow-hidden">
      <motion.div
        initial="hidden"
        animate="show"
        variants={staggerContainer}
        className="flex flex-col items-center justify-center px-8 py-16 text-center"
      >
        {Icon ? (
          <motion.div variants={fadeUp}>
            <div
              className={cn(
                "mb-4 inline-flex rounded-2xl border p-4",
                isLight ? "border-neutral-200 bg-neutral-50 text-neutral-400" : "border-neutral-800 bg-neutral-900/60 text-neutral-500",
              )}
            >
              <Icon className="h-8 w-8" aria-hidden />
            </div>
          </motion.div>
        ) : null}
        <motion.div
          variants={fadeUp}
          className={cn("text-base font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}
        >
          {title}
        </motion.div>
        {description ? (
          <motion.p
            variants={fadeUp}
            className={cn("mt-2 max-w-md text-sm", isLight ? "text-neutral-600" : "text-neutral-500")}
          >
            {description}
          </motion.p>
        ) : null}
        {action ? (
          <motion.div variants={fadeUp} className="mt-6">
            {action}
          </motion.div>
        ) : null}
      </motion.div>
    </GlassPanel>
  );
}
