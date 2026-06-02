import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";

export function AdvEnhancedDashboard() {
  const isDark = useUiThemeStore((s) => s.mode === "dark");
  const [activeTab, setActiveTab] = useState("dashboard");
  const [dashboardData, setDashboardData] = useState({
    nifty: 20485,
    signals: 58,
    pnl: 12450,
    winRate: 62,
  });

  // Fetch real data from API
  useEffect(() => {
    const fetchData = async () => {
      try {
        const response = await fetch("/api/trader/me/dashboard-metrics");
        if (response.ok) {
          const result = await response.json();
          if (result.data) {
            setDashboardData({
              nifty: result.data.niftyPrice || 20485,
              signals: result.data.todaySignals || 58,
              pnl: result.data.todayPnL || 12450,
              winRate: result.data.winRate || 62,
            });
          }
        }
      } catch (error) {
        console.log("Using demo data - API unavailable");
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 5000); // Refresh every 5 seconds
    return () => clearInterval(interval);
  }, []);

  const tabs = [
    { id: "dashboard", label: "📊 Dashboard", icon: "📊" },
    { id: "intelligence", label: "💡 Intelligence", icon: "💡" },
    { id: "patterns", label: "🎯 Patterns", icon: "🎯" },
    { id: "analytics", label: "📈 Analytics", icon: "📈" },
    { id: "execution", label: "⚡ Execution", icon: "⚡" },
    { id: "portfolio", label: "💼 Portfolio", icon: "💼" },
    { id: "advanced", label: "⚙️ Advanced", icon: "⚙️" },
    { id: "trading", label: "🎯 Live Trading", icon: "🎯" },
  ];

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className={cn(
        "h-full w-full rounded-xl border overflow-auto",
        isDark
          ? "border-neutral-800 bg-neutral-900"
          : "border-neutral-200 bg-gradient-to-br from-neutral-50 via-blue-50 to-neutral-50"
      )}
    >
      <div className="p-6 space-y-6">
        {/* HEADER */}
        <div
          className={cn(
            "rounded-2xl border p-6 transition-all",
            isDark
              ? "border-neutral-800 bg-neutral-800/50"
              : "border-neutral-200 bg-white shadow-lg"
          )}
        >
          <div className="flex items-start justify-between gap-6 mb-6">
            <div className="flex items-center gap-4">
              <div
                className={cn(
                  "rounded-2xl w-16 h-16 flex items-center justify-center text-2xl font-bold",
                  isDark ? "bg-blue-600/20" : "bg-gradient-to-br from-blue-500 to-blue-600 text-white"
                )}
              >
                ⚡
              </div>
              <div>
                <div className="text-xs font-bold text-blue-500 uppercase tracking-wide">
                  Institutional Grade
                </div>
                <div className="text-2xl font-bold">Stokr Elite Enhanced</div>
              </div>
            </div>

            <div className="text-xs font-bold text-neutral-500 uppercase">
              {new Date().toLocaleTimeString("en-IN", {
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
              })}
            </div>
          </div>

          {/* METRICS */}
          <div className="grid grid-cols-4 gap-4">
            <div className="text-center">
              <div className="text-xs font-bold text-neutral-500 uppercase mb-1">NIFTY 50</div>
              <div className="text-2xl font-bold text-blue-600">
                {dashboardData.nifty.toLocaleString("en-IN", { maximumFractionDigits: 0 })}
              </div>
            </div>
            <div className="text-center">
              <div className="text-xs font-bold text-neutral-500 uppercase mb-1">Active Signals</div>
              <div className="text-2xl font-bold text-green-500">{dashboardData.signals} 🟢</div>
            </div>
            <div className="text-center">
              <div className="text-xs font-bold text-neutral-500 uppercase mb-1">Today P&L</div>
              <div className="text-2xl font-bold text-green-500">
                +₹{dashboardData.pnl.toLocaleString("en-IN")}
              </div>
            </div>
            <div className="text-center">
              <div className="text-xs font-bold text-neutral-500 uppercase mb-1">Win Rate</div>
              <div className="text-2xl font-bold text-blue-600">{dashboardData.winRate}%</div>
            </div>
          </div>
        </div>

        {/* TABS */}
        <div
          className={cn(
            "rounded-2xl border p-3 flex gap-2 flex-wrap",
            isDark ? "border-neutral-800 bg-neutral-800/30" : "border-neutral-200 bg-white shadow-sm"
          )}
        >
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={cn(
                "px-4 py-2 rounded-lg text-sm font-semibold whitespace-nowrap transition-all",
                activeTab === tab.id
                  ? isDark
                    ? "bg-blue-600/30 text-blue-400 border border-blue-500/50"
                    : "bg-blue-50 text-blue-700 border border-blue-200"
                  : isDark
                  ? "text-neutral-400 hover:bg-neutral-700/50"
                  : "text-neutral-600 hover:bg-neutral-100"
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* CONTENT AREA */}
        <div
          className={cn(
            "rounded-2xl border p-6 min-h-96",
            isDark ? "border-neutral-800 bg-neutral-800/20" : "border-neutral-200 bg-white shadow-sm"
          )}
        >
          {activeTab === "dashboard" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">📊 Trading Dashboard</h3>
              <div className="grid grid-cols-2 gap-4">
                <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-blue-50")}>
                  <div className="text-sm text-neutral-600 uppercase mb-2">Today P&L</div>
                  <div className="text-2xl font-bold text-green-500">+₹{dashboardData.pnl.toLocaleString("en-IN")}</div>
                  <div className="text-xs text-green-600 mt-1">↑ 4 trades</div>
                </div>
                <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-blue-50")}>
                  <div className="text-sm text-neutral-600 uppercase mb-2">Win Rate (30d)</div>
                  <div className="text-2xl font-bold text-blue-600">{dashboardData.winRate}%</div>
                  <div className="text-xs text-blue-600 mt-1">↑ +5% vs avg</div>
                </div>
                <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-blue-50")}>
                  <div className="text-sm text-neutral-600 uppercase mb-2">Avg Trade</div>
                  <div className="text-2xl font-bold">₹3,112</div>
                  <div className="text-xs text-green-600 mt-1">↑ Profitable</div>
                </div>
                <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-blue-50")}>
                  <div className="text-sm text-neutral-600 uppercase mb-2">Capital Used</div>
                  <div className="text-2xl font-bold">42%</div>
                  <div className="text-xs text-neutral-600 mt-1">Available: ₹1.85L</div>
                </div>
              </div>
            </div>
          )}

          {activeTab === "intelligence" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">💡 Signal Intelligence</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Signal analysis with AI scores and confidence levels
              </p>
              <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-blue-50")}>
                <div className="text-sm font-bold">Active Signals: {dashboardData.signals}</div>
              </div>
            </div>
          )}

          {activeTab === "patterns" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">🎯 Pattern Recognition</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Chart patterns with success rates and probability analysis
              </p>
            </div>
          )}

          {activeTab === "analytics" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">📈 Performance Analytics</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Detailed performance metrics and backtesting results
              </p>
            </div>
          )}

          {activeTab === "execution" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">⚡ Order Execution</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Real-time order execution timeline and order book
              </p>
            </div>
          )}

          {activeTab === "portfolio" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">💼 Portfolio Management</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Position allocation and holdings with P&L tracking
              </p>
            </div>
          )}

          {activeTab === "advanced" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">⚙️ Advanced Settings</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Configuration, alerts, and risk management settings
              </p>
            </div>
          )}

          {activeTab === "trading" && (
            <div className="space-y-4">
              <h3 className="text-lg font-bold">🎯 Live Trading</h3>
              <p className={isDark ? "text-neutral-400" : "text-neutral-600"}>
                Real-time order entry, position management, and live execution
              </p>
              <div className={cn("p-4 rounded-lg", isDark ? "bg-neutral-700/50" : "bg-green-50")}>
                <div className="text-sm font-bold text-green-700">Ready for Live Trading</div>
              </div>
            </div>
          )}
        </div>
      </div>
    </motion.div>
  );
}
