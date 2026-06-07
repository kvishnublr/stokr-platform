import { motion, type HTMLMotionProps } from "framer-motion";
import { ReactNode } from "react";
import { cn } from "../../lib/utils";

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "sm" | "md" | "lg";

interface ModernButtonProps extends HTMLMotionProps<"button"> {
  children: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: ReactNode;
  loading?: boolean;
  className?: string;
}

const variantStyles: Record<ButtonVariant, string> = {
  primary: "bg-gradient-to-r from-indigo-500 to-purple-600 text-white hover:shadow-lg hover:shadow-indigo-500/50",
  secondary: "bg-white/10 border border-white/20 text-white hover:bg-white/20 hover:border-white/30",
  ghost: "text-white hover:bg-white/10",
  danger: "bg-gradient-to-r from-red-500 to-pink-600 text-white hover:shadow-lg hover:shadow-red-500/50",
};

const sizeStyles: Record<ButtonSize, string> = {
  sm: "px-3 py-1.5 text-sm rounded-lg",
  md: "px-4 py-2.5 text-base rounded-xl",
  lg: "px-6 py-3.5 text-lg rounded-xl",
};

export function ModernButton({
  children,
  variant = "primary",
  size = "md",
  icon,
  loading = false,
  className,
  disabled,
  ...props
}: ModernButtonProps) {
  return (
    <motion.button
      {...props}
      disabled={loading || disabled}
      className={cn(
        "font-semibold transition-all duration-300 flex items-center justify-center gap-2",
        variantStyles[variant],
        sizeStyles[size],
        (loading || disabled) && "opacity-50 cursor-not-allowed",
        className,
      )}
      whileHover={{ scale: 1.02 }}
      whileTap={{ scale: 0.98 }}
    >
      {loading ? (
        <motion.span animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity }}>
          ⟳
        </motion.span>
      ) : (
        icon
      )}
      {children}
    </motion.button>
  );
}
