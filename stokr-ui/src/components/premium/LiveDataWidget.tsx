import { motion } from 'framer-motion';
import { ReactNode } from 'react';

interface LiveDataWidgetProps {
  label: string;
  value: string | number;
  unit?: string;
  change?: number;
  isPositive?: boolean;
  icon?: ReactNode;
  isLive?: boolean;
  trend?: 'up' | 'down' | 'neutral';
  animated?: boolean;
}

export function LiveDataWidget({
  label,
  value,
  unit,
  change,
  isPositive,
  icon,
  isLive = true,
  trend = 'neutral',
  animated = true
}: LiveDataWidgetProps) {
  const trendColor = trend === 'up' ? 'text-emerald-400' : trend === 'down' ? 'text-red-500' : 'text-cyan-400';
  const changeBg = isPositive ? 'bg-emerald-400/10' : 'bg-red-500/10';

  return (
    <motion.div
      initial={animated ? { opacity: 0, y: 10 } : {}}
      animate={animated ? { opacity: 1, y: 0 } : {}}
      className="relative"
    >
      {/* Background shimmer for live updates */}
      {isLive && (
        <motion.div
          animate={{ backgroundPosition: ['0% 0%', '100% 0%', '0% 0%'] }}
          transition={{ duration: 3, repeat: Infinity }}
          style={{
            backgroundImage: 'linear-gradient(90deg, transparent, rgba(0,217,255,0.2), transparent)',
            backgroundSize: '200% 100%'
          }}
          className="absolute inset-0 rounded-lg pointer-events-none"
        />
      )}

      <div className="relative p-4 rounded-lg border border-cyan-500/20 bg-slate-900/40 backdrop-blur-xl shadow-md">
        {/* Live indicator */}
        {isLive && (
          <motion.div
            animate={{ opacity: [1, 0.5, 1] }}
            transition={{ duration: 2, repeat: Infinity }}
            className="absolute top-3 right-3 w-2 h-2 rounded-full bg-cyan-400"
          />
        )}

        {/* Label */}
        <div className="flex items-center gap-2 mb-2">
          {icon && <div className="text-cyan-400">{icon}</div>}
          <span className="text-text-secondary text-body-sm font-medium">{label}</span>
        </div>

        {/* Value with number counter animation */}
        <motion.div
          animate={animated ? { opacity: [0.8, 1] } : {}}
          transition={{ duration: 0.6 }}
          className="flex items-baseline gap-1"
        >
          <span className={`font-mono-lg font-bold text-white ${trendColor}`}>
            {value}
          </span>
          {unit && <span className="text-text-tertiary text-body-sm">{unit}</span>}
        </motion.div>

        {/* Change indicator */}
        {change !== undefined && (
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.2 }}
            className={`mt-2 inline-block px-2 py-1 rounded text-xs font-mono ${changeBg} ${trendColor}`}
          >
            {isPositive ? '+' : ''}{change}%
          </motion.div>
        )}
      </div>
    </motion.div>
  );
}

export default LiveDataWidget;
