import { motion } from 'framer-motion';
import clsx from 'clsx';

interface LiveDataWidgetProps {
  label: string;
  value: string | number;
  unit?: string;
  change?: number;
  isPositive?: boolean;
  isLive?: boolean;
  trend?: 'up' | 'down' | 'neutral';
}

export function LiveDataWidget({
  label,
  value,
  unit,
  change,
  isPositive = true,
  isLive = false,
  trend = 'neutral'
}: LiveDataWidgetProps) {
  return (
    <div className="space-y-1.5">
      <div className="flex justify-between items-center">
        <span className="text-text-tertiary text-xs font-medium">{label}</span>
        {isLive && (
          <motion.div
            animate={{ opacity: [1, 0.5, 1] }}
            transition={{ duration: 2, repeat: Infinity }}
            className="flex items-center gap-1"
          >
            <div className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            <span className="text-emerald-400 text-xs font-mono">LIVE</span>
          </motion.div>
        )}
      </div>

      {/* Value display with shimmer effect */}
      <motion.div
        animate={isLive ? { opacity: [0.8, 1, 0.8] } : {}}
        transition={isLive ? { duration: 2, repeat: Infinity } : {}}
        className="flex items-baseline gap-2"
      >
        <span className="font-mono text-lg font-bold text-white">
          {value}
        </span>
        {unit && <span className="text-text-secondary text-sm">{unit}</span>}
      </motion.div>

      {/* Change badge */}
      {change !== undefined && (
        <motion.div
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          className={clsx(
            'text-xs font-mono font-semibold w-fit px-2 py-1 rounded',
            isPositive
              ? 'bg-emerald-500/20 text-emerald-400'
              : 'bg-red-500/20 text-red-500'
          )}
        >
          {isPositive ? '↑' : '↓'} {Math.abs(change).toFixed(2)}%
        </motion.div>
      )}

      {/* Trend indicator */}
      {trend !== 'neutral' && (
        <div className="flex items-center gap-1">
          <span className={clsx(
            'text-xs font-semibold',
            trend === 'up' ? 'text-emerald-400' : 'text-red-500'
          )}>
            {trend === 'up' ? '↑ Uptrend' : '↓ Downtrend'}
          </span>
        </div>
      )}
    </div>
  );
}

export default LiveDataWidget;
