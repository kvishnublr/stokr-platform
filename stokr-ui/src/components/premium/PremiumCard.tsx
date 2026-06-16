import { motion } from 'framer-motion';
import clsx from 'clsx';
import { ReactNode } from 'react';

interface PremiumCardProps {
  children: ReactNode;
  glow?: 'blue' | 'emerald' | 'red' | 'purple' | 'none';
  interactive?: boolean;
  animated?: boolean;
  status?: 'default' | 'active' | 'warning' | 'error' | 'success';
  className?: string;
}

const glowClasses = {
  blue: 'shadow-glow',
  emerald: 'shadow-glow-emerald',
  red: 'shadow-glow-red',
  purple: 'shadow-glow-purple',
  none: ''
};

const statusClasses = {
  default: 'border-white/10',
  active: 'border-cyan-500/40',
  warning: 'border-purple-500/30',
  error: 'border-red-500/40',
  success: 'border-emerald-500/40'
};

export function PremiumCard({
  children,
  glow = 'blue',
  interactive = false,
  animated = true,
  status = 'default',
  className
}: PremiumCardProps) {
  return (
    <motion.div
      initial={animated ? { opacity: 0, y: 20 } : {}}
      animate={animated ? { opacity: 1, y: 0 } : {}}
      whileHover={interactive ? { y: -4 } : {}}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      className={clsx(
        'relative rounded-lg border backdrop-blur-xl',
        'bg-slate-900/40',
        statusClasses[status],
        'shadow-[0_4px_12px_rgba(0,0,0,0.2)]',
        'shadow-inner shadow-white/5',
        'transition-all duration-300',
        interactive && 'cursor-pointer hover:shadow-[0_8px_24px_rgba(0,0,0,0.3)]',
        glowClasses[glow],
        className
      )}
    >
      {/* Animated border glow on hover */}
      {interactive && (
        <motion.div
          whileHover={{ opacity: 1 }}
          initial={{ opacity: 0 }}
          className="absolute inset-0 rounded-lg border border-cyan-400/40 pointer-events-none"
        />
      )}

      {/* Content */}
      <div className="relative">
        {children}
      </div>
    </motion.div>
  );
}

export default PremiumCard;
