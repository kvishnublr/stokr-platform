import { motion } from 'framer-motion';
import clsx from 'clsx';

interface StrategyCardProps {
  strategyName: string;
  symbol: string;
  status: 'running' | 'cooling' | 'idle' | 'blocked';
  confidence: number;
  tradeProb: number;
  regime?: string;
  riskWinRatio?: string;
  signalStrength: number;
  activeOrders?: number;
  dayPnL?: number;
  drawdown?: number;
}

const statusConfig = {
  running: {
    bg: 'bg-gradient-to-br from-emerald-900/40 to-emerald-800/20',
    border: 'border-emerald-500/40',
    label: 'RUNNING',
    glow: 'shadow-[0_0_20px_rgba(0,255,159,0.3)]'
  },
  cooling: {
    bg: 'bg-gradient-to-br from-purple-900/40 to-purple-800/20',
    border: 'border-purple-500/40',
    label: 'COOLING DOWN',
    glow: 'shadow-[0_0_20px_rgba(199,125,255,0.3)]'
  },
  idle: {
    bg: 'bg-gradient-to-br from-slate-900/40 to-slate-800/20',
    border: 'border-slate-600/40',
    label: 'IDLE',
    glow: ''
  },
  blocked: {
    bg: 'bg-gradient-to-br from-red-900/40 to-red-800/20',
    border: 'border-red-500/40',
    label: 'BLOCKED',
    glow: 'shadow-[0_0_20px_rgba(255,56,96,0.3)]'
  }
};

const ConfidenceGauge = ({ value }: { value: number }) => {
  const color = value >= 70 ? 'emerald' : value >= 50 ? 'cyan' : 'purple';
  const colorClass =
    color === 'emerald'
      ? 'bg-emerald-500'
      : color === 'cyan'
        ? 'bg-cyan-400'
        : 'bg-purple-400';

  return (
    <div className="space-y-1">
      <div className="flex justify-between items-center">
        <span className="text-text-tertiary text-xs">Confidence</span>
        <span className="font-mono text-xs font-bold text-white">{value}%</span>
      </div>
      <div className="w-full h-1.5 bg-slate-800 rounded-full overflow-hidden border border-slate-700/50">
        <motion.div
          initial={{ width: 0 }}
          animate={{ width: `${value}%` }}
          transition={{ type: 'spring', stiffness: 100, damping: 20 }}
          className={clsx(colorClass, 'h-full rounded-full shadow-lg')}
        />
      </div>
    </div>
  );
};

const SignalPulse = ({ strength }: { strength: number }) => {
  const isActive = strength > 30;

  return (
    <div className="flex items-center gap-2">
      <motion.div
        animate={isActive ? { scale: [1, 1.2, 1], opacity: [1, 0.6, 1] } : {}}
        transition={{ duration: 1.5, repeat: Infinity }}
        className={clsx(
          'w-2 h-2 rounded-full',
          isActive ? 'bg-cyan-400 shadow-glow' : 'bg-slate-600'
        )}
      />
      <span className="text-xs text-text-secondary">
        Signal: <span className="text-white font-mono">{strength}%</span>
      </span>
    </div>
  );
};

export function StrategyCard({
  strategyName,
  symbol,
  status,
  confidence,
  tradeProb,
  regime,
  riskWinRatio,
  signalStrength,
  activeOrders = 0,
  dayPnL,
  drawdown
}: StrategyCardProps) {
  const config = statusConfig[status];

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -4 }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      className={clsx(
        'relative overflow-hidden rounded-lg border p-5',
        'backdrop-blur-xl',
        config.bg,
        config.border,
        'shadow-[0_4px_12px_rgba(0,0,0,0.2)]',
        'shadow-inner shadow-white/5',
        'transition-all duration-300 cursor-pointer',
        'hover:border-cyan-400/60',
        config.glow
      )}
    >
      {/* Animated gradient border on hover */}
      <motion.div
        whileHover={{ opacity: 1 }}
        initial={{ opacity: 0 }}
        className="absolute inset-0 rounded-lg border border-cyan-400/40 pointer-events-none"
      />

      {/* Content */}
      <div className="relative space-y-4">
        {/* Header */}
        <div className="flex items-start justify-between">
          <div>
            <h3 className="text-sm font-semibold text-white">{strategyName}</h3>
            <p className="text-text-secondary text-xs mt-0.5">{symbol}</p>
          </div>
          <motion.div
            animate={{ opacity: [1, 0.7, 1] }}
            transition={{ duration: 1.5, repeat: Infinity }}
            className={clsx(
              'px-2 py-1 rounded text-xs font-bold',
              status === 'running' && 'bg-emerald-500/20 text-emerald-400',
              status === 'cooling' && 'bg-purple-500/20 text-purple-400',
              status === 'idle' && 'bg-slate-600/20 text-slate-400',
              status === 'blocked' && 'bg-red-500/20 text-red-400'
            )}
          >
            {config.label}
          </motion.div>
        </div>

        {/* Metrics Grid */}
        <div className="grid grid-cols-2 gap-3">
          {/* Confidence Gauge */}
          <div className="col-span-2">
            <ConfidenceGauge value={confidence} />
          </div>

          {/* Trade Probability */}
          <div className="space-y-1">
            <span className="text-text-tertiary text-xs">Trade Prob</span>
            <p className="font-mono text-sm font-bold text-cyan-400">{tradeProb}%</p>
          </div>

          {/* Active Orders */}
          {activeOrders > 0 && (
            <div className="space-y-1">
              <span className="text-text-tertiary text-xs">Active Orders</span>
              <motion.p
                animate={{ opacity: [0.7, 1] }}
                transition={{ duration: 1, repeat: Infinity }}
                className="font-mono text-sm font-bold text-emerald-400"
              >
                {activeOrders}
              </motion.p>
            </div>
          )}

          {/* Regime */}
          {regime && (
            <div className="space-y-1">
              <span className="text-text-tertiary text-xs">Regime</span>
              <p className="text-xs text-cyan-300 font-semibold">{regime}</p>
            </div>
          )}

          {/* Risk/Reward */}
          {riskWinRatio && (
            <div className="space-y-1">
              <span className="text-text-tertiary text-xs">R/W Ratio</span>
              <p className="font-mono text-xs text-purple-400 font-bold">{riskWinRatio}</p>
            </div>
          )}
        </div>

        {/* Signal Pulse */}
        <div className="pt-2 border-t border-white/5">
          <SignalPulse strength={signalStrength} />
        </div>

        {/* PnL indicator */}
        {dayPnL !== undefined && (
          <motion.div
            animate={{ opacity: [0.8, 1] }}
            transition={{ duration: 0.6 }}
            className={clsx(
              'text-sm font-mono font-bold',
              dayPnL > 0 ? 'text-emerald-400' : 'text-red-500'
            )}
          >
            Day PnL: {dayPnL > 0 ? '+' : ''}{dayPnL}%
          </motion.div>
        )}
      </div>
    </motion.div>
  );
}

export default StrategyCard;
