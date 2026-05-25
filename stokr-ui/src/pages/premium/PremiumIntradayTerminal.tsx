import { motion } from 'framer-motion';
import { useState, useEffect } from 'react';
import clsx from 'clsx';
import { PremiumCard } from '@/components/premium/PremiumCard';
import { LiveDataWidget } from '@/components/premium/LiveDataWidget';
import { StrategyCard } from '@/components/premium/StrategyCard';

/**
 * PREMIUM INTRADAY TRADING TERMINAL
 *
 * An institutional-grade trading experience with:
 * - Glassmorphic design
 * - Real-time animations
 * - Premium visual hierarchy
 * - Trader-focused UX
 * - High-frequency data presentation
 */

export function PremiumIntradayTerminal() {
  const [selectedStrategy, setSelectedStrategy] = useState<string | null>(null);
  const [isLiveMarket, setIsLiveMarket] = useState(true);

  // Mock data - replace with real data from API
  const marketData = {
    nifty: 23085.50,
    banknifty: 47620.30,
    vix: 34.3,
    breadth: 1250,
    advanceDecline: '2.1:1'
  };

  const strategies = [
    {
      id: 'gap-fills',
      name: 'Gap Fills',
      symbol: 'NIFTY_50',
      status: 'running' as const,
      confidence: 78,
      tradeProb: 68,
      regime: 'MEAN_REVERSION',
      signalStrength: 85,
      activeOrders: 2,
      dayPnL: 2.4
    },
    {
      id: 'vwap-breakout',
      name: 'VWAP Breakout',
      symbol: 'BANKNIFTY',
      status: 'cooling' as const,
      confidence: 64,
      tradeProb: 52,
      regime: 'CHOPPY',
      signalStrength: 55,
      activeOrders: 0,
      dayPnL: -0.8
    },
    {
      id: 'momentum',
      name: 'Momentum',
      symbol: 'FINNIFTY',
      status: 'idle' as const,
      confidence: 48,
      tradeProb: 38,
      regime: 'SIDEWAYS',
      signalStrength: 35,
      activeOrders: 0
    }
  ];

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-950 via-blue-950 to-slate-950">
      {/* Animated background gradient */}
      <motion.div
        animate={{
          backgroundPosition: ['0% 0%', '100% 100%', '0% 0%']
        }}
        transition={{ duration: 20, repeat: Infinity }}
        style={{
          backgroundImage:
            'radial-gradient(circle at 20% 50%, rgba(0,217,255,0.1) 0%, transparent 50%), radial-gradient(circle at 80% 80%, rgba(0,255,159,0.1) 0%, transparent 50%)',
          backgroundSize: '200% 200%'
        }}
        className="fixed inset-0 pointer-events-none"
      />

      {/* Main content */}
      <div className="relative">
        {/* Header with Market Overview */}
        <motion.header
          initial={{ opacity: 0, y: -20 }}
          animate={{ opacity: 1, y: 0 }}
          className="sticky top-0 z-40 border-b border-cyan-500/10 bg-slate-950/80 backdrop-blur-xl shadow-lg"
        >
          <div className="max-w-7xl mx-auto px-6 py-4">
            <div className="flex items-center justify-between mb-4">
              <h1 className="text-heading-lg font-bold text-white">Intraday Trading Terminal</h1>
              <div className="flex items-center gap-4">
                <motion.div
                  animate={{ scale: [1, 1.1, 1] }}
                  transition={{ duration: 2, repeat: Infinity }}
                  className="flex items-center gap-2 px-4 py-2 rounded-lg bg-emerald-500/10 border border-emerald-500/30"
                >
                  <div className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  <span className="text-emerald-400 text-sm font-mono">MARKET OPEN</span>
                </motion.div>
                <div className="text-text-secondary text-sm">Last update: 10:45:32 AM</div>
              </div>
            </div>

            {/* Market Indices Strip */}
            <motion.div
              layout
              className="grid grid-cols-5 gap-3"
            >
              {[
                { label: 'NIFTY 50', value: marketData.nifty.toFixed(2), change: 0.05 },
                { label: 'BANKNIFTY', value: marketData.banknifty.toFixed(2), change: -0.20 },
                { label: 'VIX', value: marketData.vix.toFixed(1), change: 2.1, isAlert: true },
                { label: 'Breadth', value: marketData.breadth.toString(), change: 45 },
                { label: 'Adv/Dec', value: marketData.advanceDecline, change: 0.15 }
              ].map((item, idx) => (
                <motion.div
                  key={idx}
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: idx * 0.05 }}
                  className={clsx(
                    'p-3 rounded-lg border backdrop-blur-xl',
                    item.isAlert
                      ? 'bg-red-900/20 border-red-500/30'
                      : 'bg-slate-900/40 border-cyan-500/20'
                  )}
                >
                  <p className="text-text-tertiary text-xs font-semibold">{item.label}</p>
                  <motion.p
                    animate={{ opacity: [0.8, 1] }}
                    transition={{ duration: 0.6 }}
                    className="font-mono text-sm font-bold text-white"
                  >
                    {item.value}
                  </motion.p>
                  <motion.span
                    initial={{ opacity: 0, scale: 0.9 }}
                    animate={{ opacity: 1, scale: 1 }}
                    className={clsx(
                      'text-xs font-mono',
                      item.change >= 0 ? 'text-emerald-400' : 'text-red-500'
                    )}
                  >
                    {item.change >= 0 ? '+' : ''}{item.change}%
                  </motion.span>
                </motion.div>
              ))}
            </motion.div>
          </div>
        </motion.header>

        {/* Main Grid Layout */}
        <main className="max-w-7xl mx-auto px-6 py-8">
          <div className="grid grid-cols-12 gap-6">
            {/* Left Sidebar - Navigation & Quick Stats */}
            <motion.aside
              initial={{ opacity: 0, x: -20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.1 }}
              className="col-span-3 space-y-4"
            >
              {/* Quick Stats */}
              <PremiumCard glow="blue" className="p-5">
                <div className="space-y-4">
                  <h3 className="font-heading-sm text-white">Portfolio Overview</h3>

                  <LiveDataWidget
                    label="Day PnL"
                    value="+2,450"
                    unit="₹"
                    change={1.24}
                    isPositive={true}
                    isLive={true}
                  />

                  <div className="pt-4 border-t border-white/10">
                    <LiveDataWidget
                      label="Risk Used"
                      value="46"
                      unit="%"
                      isLive={true}
                    />
                  </div>

                  <motion.div
                    whileHover={{ x: 4 }}
                    className="p-3 rounded-lg bg-cyan-500/10 border border-cyan-500/30 text-cyan-400 text-xs cursor-pointer"
                  >
                    → View Full Dashboard
                  </motion.div>
                </div>
              </PremiumCard>

              {/* Active Strategies Summary */}
              <PremiumCard glow="emerald" className="p-5">
                <div className="space-y-3">
                  <h3 className="font-heading-sm text-white">Active Strategies</h3>

                  {strategies
                    .filter(s => s.status !== 'idle')
                    .map(strategy => (
                      <motion.div
                        key={strategy.id}
                        whileHover={{ x: 4 }}
                        onClick={() => setSelectedStrategy(strategy.id)}
                        className={clsx(
                          'p-3 rounded-lg cursor-pointer transition-all',
                          selectedStrategy === strategy.id
                            ? 'bg-cyan-500/20 border border-cyan-400/50'
                            : 'bg-slate-800/30 border border-white/10 hover:border-cyan-400/30'
                        )}
                      >
                        <div className="flex items-start justify-between">
                          <div>
                            <p className="text-sm font-bold text-white">{strategy.name}</p>
                            <p className="text-xs text-text-secondary">{strategy.symbol}</p>
                          </div>
                          <motion.div
                            animate={
                              strategy.status === 'running'
                                ? { scale: [1, 1.1, 1], opacity: [1, 0.6, 1] }
                                : {}
                            }
                            transition={{ duration: 1.5, repeat: Infinity }}
                            className={clsx(
                              'w-2 h-2 rounded-full',
                              strategy.status === 'running'
                                ? 'bg-emerald-400'
                                : 'bg-purple-400'
                            )}
                          />
                        </div>
                        {strategy.dayPnL !== undefined && (
                          <p
                            className={clsx(
                              'text-xs font-mono mt-2',
                              strategy.dayPnL > 0 ? 'text-emerald-400' : 'text-red-500'
                            )}
                          >
                            {strategy.dayPnL > 0 ? '+' : ''}{strategy.dayPnL}%
                          </p>
                        )}
                      </motion.div>
                    ))}
                </div>
              </PremiumCard>
            </motion.aside>

            {/* Center - Strategy Rail & Opportunities */}
            <motion.section
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.2 }}
              className="col-span-4 space-y-4"
            >
              <h2 className="font-heading-md text-white px-2">Strategy Rail</h2>

              <div className="space-y-3 max-h-[600px] overflow-y-auto pr-2">
                {strategies.map((strategy, idx) => (
                  <motion.div
                    key={strategy.id}
                    initial={{ opacity: 0, y: 20 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: 0.3 + idx * 0.1 }}
                  >
                    <StrategyCard
                      strategyName={strategy.name}
                      symbol={strategy.symbol}
                      status={strategy.status}
                      confidence={strategy.confidence}
                      tradeProb={strategy.tradeProb}
                      regime={strategy.regime}
                      signalStrength={strategy.signalStrength}
                      activeOrders={strategy.activeOrders}
                      dayPnL={strategy.dayPnL}
                    />
                  </motion.div>
                ))}
              </div>
            </motion.section>

            {/* Right - Execution Intel & Live Metrics */}
            <motion.section
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.3 }}
              className="col-span-5 space-y-4"
            >
              <h2 className="font-heading-md text-white px-2">Execution Intelligence</h2>

              {/* Execution Quality Radar */}
              <PremiumCard glow="blue" className="p-5" status="active">
                <h3 className="font-heading-sm text-white mb-4">Execution Quality</h3>

                <div className="space-y-3">
                  {[
                    { label: 'Fill Speed', value: 94, unit: 'ms' },
                    { label: 'Slippage', value: 0.23, unit: 'pts' },
                    { label: 'Broker Health', value: 99.8, unit: '%' },
                    { label: 'Order Flow', value: 87, unit: '%' }
                  ].map((metric, idx) => (
                    <motion.div
                      key={idx}
                      initial={{ opacity: 0, x: -10 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.1 + idx * 0.05 }}
                      className="space-y-1"
                    >
                      <div className="flex justify-between text-xs">
                        <span className="text-text-secondary">{metric.label}</span>
                        <span className="font-mono font-bold text-cyan-400">
                          {metric.value}{metric.unit}
                        </span>
                      </div>
                      <div className="w-full h-1.5 bg-slate-800 rounded-full overflow-hidden">
                        <motion.div
                          initial={{ width: 0 }}
                          animate={{ width: `${Math.min(metric.value, 100)}%` }}
                          transition={{ delay: 0.2 + idx * 0.1, duration: 1 }}
                          className="h-full bg-gradient-to-r from-cyan-400 to-emerald-400 rounded-full"
                        />
                      </div>
                    </motion.div>
                  ))}
                </div>
              </PremiumCard>

              {/* Live Positions */}
              <PremiumCard glow="emerald" className="p-5">
                <h3 className="font-heading-sm text-white mb-4">Open Positions</h3>

                <div className="space-y-2">
                  {[
                    { symbol: 'RELIANCE', qty: 15, entryPrice: 2850.50, ltp: 2865.75, pnl: 2287.50 },
                    {
                      symbol: 'BANKNIFTY',
                      qty: 1,
                      entryPrice: 47600,
                      ltp: 47620.30,
                      pnl: 20.30
                    }
                  ].map((pos, idx) => (
                    <motion.div
                      key={idx}
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: 0.1 + idx * 0.05 }}
                      className="p-3 rounded-lg bg-slate-800/30 border border-white/10 hover:border-emerald-400/30 transition-all"
                    >
                      <div className="flex justify-between items-start">
                        <div>
                          <p className="text-sm font-bold text-white">{pos.symbol}</p>
                          <p className="text-xs text-text-secondary">{pos.qty} qty</p>
                        </div>
                        <motion.span
                          animate={{ opacity: [0.8, 1] }}
                          transition={{ duration: 0.6 }}
                          className="font-mono text-sm font-bold text-emerald-400"
                        >
                          +₹{pos.pnl.toFixed(0)}
                        </motion.span>
                      </div>
                      <div className="text-xs text-text-tertiary mt-2">
                        Entry: {pos.entryPrice} | LTP: {pos.ltp}
                      </div>
                    </motion.div>
                  ))}
                </div>
              </PremiumCard>

              {/* System Status */}
              <PremiumCard glow="blue" className="p-4">
                <div className="space-y-2 text-xs">
                  <div className="flex justify-between">
                    <span className="text-text-secondary">API Latency</span>
                    <motion.span
                      animate={{ opacity: [0.7, 1] }}
                      transition={{ duration: 1, repeat: Infinity }}
                      className="font-mono text-cyan-400"
                    >
                      12ms
                    </motion.span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-text-secondary">Data Feed</span>
                    <span className="text-emerald-400">● Connected</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-text-secondary">WebSocket</span>
                    <span className="text-emerald-400">● Live</span>
                  </div>
                </div>
              </PremiumCard>
            </motion.section>
          </div>
        </main>
      </div>
    </div>
  );
}

export default PremiumIntradayTerminal;
