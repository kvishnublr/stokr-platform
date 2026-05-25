import { motion } from "framer-motion";
import { useState } from "react";
import clsx from "clsx";
import { TrendingUp } from "lucide-react";
import { LightPremiumCard } from "@/components/premium/LightPremiumCard";

export function ModernLightTerminal() {
  const [selectedStrategy, setSelectedStrategy] = useState<string>("gap-fills");

  const strategies = [
    {
      id: "gap-fills",
      name: "Gap Fills",
      symbol: "NIFTY 50",
      status: "active",
      confidence: 78,
      dailyReturn: 2.4,
      signal: 85,
      activeOrders: 2,
      regime: "Mean Reversion"
    },
    {
      id: "vwap-bounce",
      name: "VWAP Bounce",
      symbol: "BANKNIFTY",
      status: "cooling",
      confidence: 64,
      dailyReturn: -0.8,
      signal: 55,
      activeOrders: 0,
      regime: "Choppy"
    },
    {
      id: "sector-lag",
      name: "Sector Laggards",
      symbol: "Nifty 500",
      status: "standby",
      confidence: 48,
      dailyReturn: 0,
      signal: 35,
      activeOrders: 0,
      regime: "Sideways"
    }
  ];

  const positions = [
    { symbol: "RELIANCE", qty: 15, entry: 2850.50, ltp: 2865.75, mtm: 2287.50 },
    { symbol: "BANKNIFTY", qty: 1, entry: 47600, ltp: 47620.30, mtm: 20.30 }
  ];

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-blue-50">
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 right-0 w-96 h-96 bg-gradient-to-br from-blue-100 to-transparent rounded-full blur-3xl opacity-30"></div>
        <div className="absolute bottom-0 left-0 w-96 h-96 bg-gradient-to-br from-emerald-100 to-transparent rounded-full blur-3xl opacity-30"></div>
      </div>

      <motion.header className="sticky top-0 z-40 backdrop-blur-xl bg-white/80 border-b border-slate-200">
        <div className="max-w-7xl mx-auto px-6 py-6">
          <div className="flex items-center justify-between mb-6">
            <div>
              <h1 className="text-3xl font-bold text-slate-900">Trading Dashboard</h1>
              <p className="text-sm text-slate-500 mt-1">Real-time strategy performance</p>
            </div>
            <motion.div animate={{ scale: [1, 1.1, 1] }} transition={{ duration: 2, repeat: Infinity }} className="flex items-center gap-2 px-4 py-2 rounded-full bg-gradient-to-r from-emerald-500/10 to-blue-500/10 border border-emerald-200">
              <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></div>
              <span className="text-sm font-semibold text-emerald-700">MARKET LIVE</span>
            </motion.div>
          </div>

          <motion.div className="grid grid-cols-5 gap-4">
            {[{ label: "NIFTY 50", value: "23,085.50", change: 0.05 }, { label: "BANKNIFTY", value: "47,620.30", change: -0.20 }, { label: "VIX", value: "34.30", change: 2.1, alert: true }, { label: "Breadth", value: "1,250", change: 45 }, { label: "Adv/Dec", value: "2.1:1", change: 0.15 }].map((item, idx) => (
              <motion.div key={idx} className={clsx("p-4 rounded-xl border transition-all", item.alert ? "bg-red-50 border-red-200" : "bg-white border-slate-200 hover:shadow-md")}>
                <p className="text-xs font-semibold text-slate-600 uppercase">{item.label}</p>
                <p className="text-lg font-bold text-slate-900 mt-2 font-mono">{item.value}</p>
                <div className={clsx("text-xs font-semibold mt-2", item.change >= 0 ? "text-emerald-600" : "text-red-600")}>
                  {item.change >= 0 ? "↑" : "↓"} {Math.abs(item.change).toFixed(2)}%
                </div>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </motion.header>

      <main className="relative max-w-7xl mx-auto px-6 py-8">
        <div className="grid grid-cols-12 gap-6">
          <div className="col-span-3 space-y-6">
            <LightPremiumCard variant="success" elevated>
              <div className="p-6">
                <div className="flex items-center gap-3 mb-4">
                  <div className="p-3 rounded-lg bg-emerald-100"><TrendingUp className="w-5 h-5 text-emerald-600" /></div>
                  <span className="text-sm font-semibold text-slate-700">Today Performance</span>
                </div>
                <div className="space-y-3">
                  <div><p className="text-xs text-slate-600 uppercase">Realized PnL</p><p className="text-3xl font-bold text-emerald-600 font-mono">+₹2,450</p></div>
                  <div className="pt-3 border-t border-emerald-200"><p className="text-xs text-slate-600 uppercase">Return</p><p className="text-2xl font-bold text-slate-900">1.24%</p></div>
                </div>
              </div>
            </LightPremiumCard>
          </div>
          <div className="col-span-4"><h2 className="text-xl font-bold text-slate-900 mb-6">Strategy Details</h2></div>
          <div className="col-span-5"><h2 className="text-xl font-bold text-slate-900 mb-6">Open Positions</h2></div>
        </div>
      </main>
    </div>
  );
}

export default ModernLightTerminal;
