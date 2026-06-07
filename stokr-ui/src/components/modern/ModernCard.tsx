import { motion, type HTMLMotionProps } from "framer-motion";
import { ReactNode } from "react";
import { cn } from "../../lib/utils";

interface ModernCardProps extends HTMLMotionProps<"div"> {
  children: ReactNode;
  title?: string;
  icon?: ReactNode;
  hoverable?: boolean;
  gradient?: string;
  className?: string;
}

export function ModernCard({
  children,
  title,
  icon,
  hoverable = true,
  gradient = "from-slate-800/50 to-slate-700/50",
  className,
  ...props
}: ModernCardProps) {
  return (
    <motion.div
      {...props}
      className={cn(
        "backdrop-blur-xl border border-white/20 rounded-2xl p-6 transition-all duration-300",
        hoverable && "hover:border-white/40 hover:bg-white/15",
        className,
      )}
      style={{
        background: `linear-gradient(135deg, rgba(15, 23, 42, 0.4), rgba(30, 41, 59, 0.2))`,
      }}
      whileHover={hoverable ? { y: -4, scale: 1.02 } : undefined}
      transition={{ duration: 0.3 }}
    >
      {title && (
        <div className="flex items-center gap-3 mb-6">
          {icon && <div className="p-2 rounded-lg bg-gradient-to-br from-indigo-500/20 to-purple-500/20">{icon}</div>}
          <h3 className="text-white font-semibold text-lg">{title}</h3>
        </div>
      )}
      {children}
    </motion.div>
  );
}
