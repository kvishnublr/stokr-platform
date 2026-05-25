import { ReactNode } from 'react';
import { motion, MotionProps } from 'framer-motion';
import clsx from 'clsx';

interface PremiumCardProps extends MotionProps {
  children: ReactNode;
  className?: string;
  glow?: 'blue' | 'emerald' | 'red' | 'purple' | 'none';
  interactive?: boolean;
  animated?: boolean;
  status?: 'default' | 'active' | 'warning' | 'error' | 'success';
}

const glowStyles = {
  blue: 'hover:shadow-[0_0_20px_rgba(0,217,255,0.3)]',
  emerald: 'hover:shadow-[0_0_20px_rgba(0,255,159,0.3)]',
  red: 'hover:shadow-[0_0_20px_rgba(255,56,96,0.3)]',
  purple: 'hover:shadow-[0_0_20px_rgba(199,125,255,0.3)]',
  none: ''
};

const statusBorder = {
  default: 'border-blue-500/20',
  active: 'border-cyan-400/40 shadow-[0_0_20px_rgba(0,217,255,0.3)]',
  warning: 'border-purple-400/30',
  error: 'border-red-500/40',
  success: 'border-emerald-400/40'
};

const statusBg = {
  default: 'bg-slate-900/40',
  active: 'bg-slate-900/60',
  warning: 'bg-slate-900/50',
  error: 'bg-slate-900/50',
  success: 'bg-slate-900/50'
};

export function PremiumCard({
  children,
  className,
  glow = 'blue',
  interactive = true,
  animated = true,
  status = 'default',
  ...motionProps
}: PremiumCardProps) {
  const baseStyles = clsx(
    // Glass morphism
    'backdrop-blur-xl',
    statusBg[status],
    statusBorder[status],
    'border rounded-lg',

    // Shadows & effects
    'shadow-[0_4px_12px_rgba(0,0,0,0.2)]',
    'shadow-inner shadow-white/5',
    glowStyles[glow],

    // Transitions
    'transition-all duration-300',

    // Interactive
    interactive && 'hover:border-cyan-400/60 cursor-pointer',

    className
  );

  const motionAnimations = animated ? {
    initial: { opacity: 0, y: 20 },
    animate: { opacity: 1, y: 0 },
    transition: { type: 'spring', stiffness: 300, damping: 30 }
  } : {};

  return (
    <motion.div
      className={baseStyles}
      whileHover={interactive ? { y: -4 } : undefined}
      {...motionAnimations}
      {...motionProps}
    >
      {children}
    </motion.div>
  );
}

export default PremiumCard;
