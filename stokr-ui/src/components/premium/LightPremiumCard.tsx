import { motion } from 'framer-motion';
import clsx from 'clsx';
import { ReactNode } from 'react';

interface LightPremiumCardProps {
  children: ReactNode;
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info';
  elevated?: boolean;
  className?: string;
}

const variantClasses = {
  default: 'border-slate-200 bg-white shadow-sm hover:shadow-md',
  success: 'border-emerald-200 bg-emerald-50 shadow-sm hover:shadow-md',
  warning: 'border-amber-200 bg-amber-50 shadow-sm hover:shadow-md',
  danger: 'border-red-200 bg-red-50 shadow-sm hover:shadow-md',
  info: 'border-blue-200 bg-blue-50 shadow-sm hover:shadow-md'
};

export function LightPremiumCard({
  children,
  variant = 'default',
  elevated = false,
  className
}: LightPremiumCardProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: 'spring', stiffness: 400, damping: 40 }}
      whileHover={{ y: -2 }}
      className={clsx(
        'relative rounded-2xl border transition-all duration-300',
        variantClasses[variant],
        elevated && 'shadow-lg hover:shadow-xl',
        className
      )}
    >
      <div className="relative">
        {children}
      </div>
    </motion.div>
  );
}

export default LightPremiumCard;
