import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";

export function AdvEnhancedDashboard() {
  const isDark = useUiThemeStore((s) => s.mode === "dark");
  const [iframeKey, setIframeKey] = useState(0);

  useEffect(() => {
    // Force reload iframe if needed
    setIframeKey((prev) => prev + 1);
  }, []);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className={cn(
        "h-full w-full rounded-xl border transition-colors duration-300",
        isDark
          ? "border-neutral-800 bg-neutral-900"
          : "border-neutral-200 bg-neutral-50"
      )}
    >
      {/* Inline ADV Dashboard Content */}
      <div className="h-full w-full overflow-auto">
        <AdvEnhancedDashboardContent />
      </div>
    </motion.div>
  );
}

// Embedded dashboard content - responsive and theme-aware
function AdvEnhancedDashboardContent() {
  const isDark = useUiThemeStore((s) => s.mode === "dark");
  const [dashboardData, setDashboardData] = useState({
    nifty: 20485,
    signals: 58,
    pnl: 12450,
    winRate: 62,
  });

  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      const formatted = now.toLocaleTimeString("en-IN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      });
      const clockEl = document.getElementById("clock");
      if (clockEl) {
        clockEl.textContent = formatted;
      }
    };

    updateClock();
    const interval = setInterval(updateClock, 1000);

    // Simulate live data updates
    const dataInterval = setInterval(() => {
      setDashboardData((prev) => ({
        nifty: Math.max(20000, prev.nifty + (Math.random() - 0.5) * 100),
        signals: Math.max(10, prev.signals + Math.floor((Math.random() - 0.5) * 10)),
        pnl: prev.pnl + Math.floor((Math.random() - 0.5) * 2000),
        winRate: Math.max(40, Math.min(90, prev.winRate + (Math.random() - 0.5) * 5)),
      }));
    }, 3000);

    return () => {
      clearInterval(interval);
      clearInterval(dataInterval);
    };
  }, []);

  return (
    <div
      className={cn(
        "min-h-screen transition-colors duration-300",
        isDark
          ? "bg-neutral-900 text-neutral-50"
          : "bg-gradient-to-br from-neutral-50 via-blue-50 to-neutral-50 text-neutral-900"
      )}
      style={{ padding: "20px" }}
    >
      {/* HEADER */}
      <div
        className={cn(
          "rounded-2xl border transition-all duration-300 mb-5",
          isDark
            ? "border-neutral-800 bg-neutral-800/50 backdrop-blur-xl shadow-lg shadow-black/20"
            : "border-neutral-200 bg-white/90 backdrop-blur-xl shadow-lg shadow-black/5"
        )}
        style={{
          padding: "24px",
          display: "grid",
          gridTemplateColumns: "auto 1fr auto",
          gap: "40px",
          alignItems: "center",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
          <div
            className={cn(
              "rounded-2xl font-bold text-2xl flex items-center justify-center",
              isDark ? "bg-blue-600/20 text-blue-400" : "bg-gradient-to-br from-blue-500 to-blue-600 text-white"
            )}
            style={{
              width: "64px",
              height: "64px",
            }}
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

        <div style={{ display: "flex", gap: "24px", justifyContent: "center" }}>
          <div style={{ textAlign: "center" }}>
            <div className="text-xs font-bold text-neutral-500 uppercase mb-1">
              NIFTY 50
            </div>
            <div
              className="text-2xl font-bold bg-gradient-to-r from-blue-500 to-blue-600 bg-clip-text text-transparent"
              id="niftyTicker"
            >
              {dashboardData.nifty.toLocaleString("en-IN", {
                maximumFractionDigits: 0,
              })}
            </div>
          </div>

          <div style={{ textAlign: "center" }}>
            <div className="text-xs font-bold text-neutral-500 uppercase mb-1">
              Active Signals
            </div>
            <div className="text-2xl font-bold text-green-500">
              {dashboardData.signals} 🟢
            </div>
          </div>

          <div style={{ textAlign: "center" }}>
            <div className="text-xs font-bold text-neutral-500 uppercase mb-1">
              Today P&L
            </div>
            <div
              className={cn(
                "text-2xl font-bold",
                dashboardData.pnl >= 0 ? "text-green-500" : "text-red-500"
              )}
            >
              {dashboardData.pnl >= 0 ? "+" : ""}₹{Math.abs(dashboardData.pnl).toLocaleString("en-IN")}
            </div>
          </div>

          <div style={{ textAlign: "center" }}>
            <div className="text-xs font-bold text-neutral-500 uppercase mb-1">
              Win Rate
            </div>
            <div className="text-2xl font-bold text-blue-500">
              {dashboardData.winRate.toFixed(1)}%
            </div>
          </div>
        </div>

        <div className="text-xs font-bold text-neutral-500 uppercase">
          <span id="clock">09:45:32</span>
        </div>
      </div>

      {/* TOOLBAR */}
      <div
        className={cn(
          "rounded-2xl border transition-all duration-300 mb-5 p-3",
          isDark
            ? "border-neutral-800 bg-neutral-800/30"
            : "border-neutral-200 bg-white shadow-sm"
        )}
        style={{
          display: "flex",
          gap: "8px",
          overflowX: "auto",
          paddingBottom: "8px",
        }}
      >
        {[
          { id: "dashboard", label: "📊 Dashboard" },
          { id: "intelligence", label: "💡 Intelligence" },
          { id: "patterns", label: "🎯 Patterns" },
          { id: "analytics", label: "📈 Analytics" },
          { id: "execution", label: "⚡ Execution" },
          { id: "portfolio", label: "💼 Portfolio" },
          { id: "advanced", label: "⚙️ Advanced" },
          { id: "trading", label: "🎯 Live Trading" },
        ].map((tab) => (
          <button
            key={tab.id}
            onClick={(e) => showTab(tab.id, e.currentTarget)}
            className={cn(
              "px-4 py-2 rounded-lg text-sm font-semibold whitespace-nowrap transition-all duration-200 cursor-pointer",
              tab.id === "dashboard"
                ? isDark
                  ? "bg-blue-600/30 text-blue-400 border border-blue-500/50"
                  : "bg-blue-50 text-blue-700 border border-blue-200"
                : isDark
                ? "text-neutral-400 hover:bg-neutral-700/50"
                : "text-neutral-600 hover:bg-neutral-100"
            )}
            style={{ border: tab.id === "dashboard" ? undefined : "none" }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* CONTENT PLACEHOLDER */}
      <div
        className={cn(
          "rounded-2xl border transition-all duration-300 p-6",
          isDark
            ? "border-neutral-800 bg-neutral-800/20"
            : "border-neutral-200 bg-white shadow-sm"
        )}
      >
        <div className="text-center py-12">
          <p className={cn("text-sm", isDark ? "text-neutral-400" : "text-neutral-600")}>
            📊 Dashboard content loading...
          </p>
          <p className={cn("text-xs mt-2", isDark ? "text-neutral-500" : "text-neutral-500")}>
            Enhanced dashboard with live trading, signals, and analytics
          </p>
        </div>
      </div>
    </div>
  );
}

// Tab switching logic
function showTab(tabId: string, button: HTMLElement) {
  // Hide all tabs
  const tabs = document.querySelectorAll(".tab-content");
  tabs.forEach((tab) => {
    (tab as HTMLElement).style.display = "none";
    (tab as HTMLElement).classList.remove("active");
  });

  // Show selected tab
  const selectedTab = document.getElementById(tabId);
  if (selectedTab) {
    selectedTab.style.display = "block";
    selectedTab.classList.add("active");
  }

  // Update button states
  const buttons = document.querySelectorAll(".tab-btn");
  buttons.forEach((btn) => btn.classList.remove("active"));
  button.classList.add("active");
}
