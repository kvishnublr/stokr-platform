import { motion } from "framer-motion";
import { ReactNode } from "react";
import { cn } from "../../lib/utils";

interface ModernInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  icon?: ReactNode;
  error?: string;
  helperText?: string;
}

export function ModernInput({
  label,
  icon,
  error,
  helperText,
  className,
  ...props
}: ModernInputProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
    >
      {label && (
        <label className="block text-sm font-semibold text-white mb-3">
          {label}
        </label>
      )}

      <div className="relative group">
        {icon && (
          <div className="absolute left-4 top-1/2 -translate-y-1/2 text-indigo-300 group-focus-within:text-indigo-200 transition-colors">
            {icon}
          </div>
        )}

        <input
          {...props}
          className={cn(
            "w-full py-3 px-4 rounded-xl",
            icon ? "pl-12" : "px-4",
            "bg-white/5 border border-white/10",
            "text-white placeholder:text-white/40",
            "focus:outline-none focus:border-indigo-400/50 focus:bg-white/10",
            "backdrop-blur-sm transition-all duration-300",
            error && "border-red-500/50 focus:border-red-500/70 bg-red-500/5",
            className,
          )}
        />
      </div>

      {error && (
        <motion.p
          initial={{ opacity: 0, y: -5 }}
          animate={{ opacity: 1, y: 0 }}
          className="text-red-400 text-sm mt-2"
        >
          {error}
        </motion.p>
      )}

      {helperText && !error && (
        <p className="text-white/50 text-sm mt-2">
          {helperText}
        </p>
      )}
    </motion.div>
  );
}
