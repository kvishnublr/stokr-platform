import { useState } from "react";
import {
  Menu,
  Sun,
  Moon,
  Bell,
  Heart,
  FileText,
  MoreVertical,
  Search,
  TrendingUp,
  TrendingDown,
  ChevronRight,
} from "lucide-react";
import { cn } from "../lib/utils";

// Enhanced typography styles
const typography = {
  h1: "text-4xl font-bold tracking-tight",
  h2: "text-2xl font-semibold tracking-tight",
  h3: "text-lg font-semibold tracking-tight",
  h4: "text-base font-semibold tracking-tight",
  h5: "text-sm font-semibold tracking-tight",
  h6: "text-xs font-semibold tracking-wide uppercase",
  subtitle1: "text-base font-normal tracking-tight leading-relaxed",
  subtitle2: "text-sm font-normal tracking-tight leading-relaxed",
  body1: "text-sm font-normal leading-relaxed",
  body2: "text-xs font-normal leading-relaxed",
  caption: "text-[11px] font-medium tracking-wider uppercase",
  monospace: "font-mono text-sm font-semibold tabular-nums",
  monosmall: "font-mono text-xs font-semibold tabular-nums",
};

type Theme = "dark" | "light";

type StatCardProps = {
  label: string;
  value: string;
  change: string;
  isPositive?: boolean;
  theme: Theme;
};

type StrategyItemProps = {
  name: string;
  pnl: string;
  mode: "LIVE" | "PAPER";
  isPositive?: boolean;
  theme: Theme;
};

type PositionItemProps = {
  symbol: string;
  quantity: string;
  price: string;
  change: string;
  isPositive?: boolean;
  theme: Theme;
};

type OrderItemProps = {
  symbol: string;
  side: "BUY" | "SELL";
  time: string;
  status: "FILLED" | "PENDING";
  theme: Theme;
};

type NewsItemProps = {
  emoji: string;
  title: string;
  timeAgo: string;
  theme: Theme;
};

type SidebarItemProps = {
  icon: string;
  label: string;
  active?: boolean;
  theme: Theme;
};

// Theme configurations
const themes = {
  dark: {
    bg: {
      primary: "#0f1419",
      secondary: "#1a2633",
      tertiary: "#242f3f",
      hover: "#2a3a4f",
    },
    text: {
      primary: "#ffffff",
      secondary: "#b4bfcc",
      tertiary: "#7a8696",
    },
    border: "#374857",
    accent: "#4169ff",
    accentLight: "#5a7cff",
    success: "#10b981",
    danger: "#ef4444",
  },
  light: {
    bg: {
      primary: "#f9fafb",
      secondary: "#f3f4f6",
      tertiary: "#ffffff",
      hover: "#eff0f5",
    },
    text: {
      primary: "#111827",
      secondary: "#4b5563",
      tertiary: "#9ca3af",
    },
    border: "#e5e7eb",
    accent: "#4169ff",
    accentLight: "#5a7cff",
    success: "#10b981",
    danger: "#ef4444",
  },
};

const StatCard: React.FC<StatCardProps> = ({
  label,
  value,
  change,
  isPositive,
  theme,
}) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-xl group cursor-pointer"
      style={{
        background:
          theme === "dark"
            ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
            : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
        borderColor: t.border,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = t.accentLight;
        e.currentTarget.style.boxShadow = `0 20px 40px ${
          theme === "dark" ? "rgba(65, 105, 255, 0.15)" : "rgba(65, 105, 255, 0.1)"
        }`;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = t.border;
        e.currentTarget.style.boxShadow = "none";
      }}
    >
      <div className="flex justify-between items-start">
        <div className="flex-1">
          <div
            className="text-[11px] font-semibold tracking-wider uppercase mb-3"
            style={{ color: t.text.tertiary }}
          >
            {label}
          </div>
          <div
            className="text-3xl font-bold mb-2 tracking-tight"
            style={{ color: t.text.primary }}
          >
            {value}
          </div>
          <div
            className="text-sm font-semibold"
            style={{
              color:
                isPositive === false ? t.danger : t.success,
            }}
          >
            {change}
          </div>
        </div>
        <div
          className="text-lg opacity-0 group-hover:opacity-100 transition-opacity"
          style={{ color: t.text.tertiary }}
        >
          ⋯
        </div>
      </div>
    </div>
  );
};

const StrategyItem: React.FC<StrategyItemProps> = ({
  name,
  pnl,
  mode,
  isPositive,
  theme,
}) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-lg p-3 mb-2 flex justify-between items-center border transition-all duration-200 group hover:shadow-md"
      style={{
        background: t.bg.secondary,
        borderColor: t.border,
      }}
    >
      <div
        className="font-semibold text-sm group-hover:translate-x-1 transition-transform"
        style={{ color: t.text.primary }}
      >
        {name}
      </div>
      <div className="flex gap-2 items-center">
        <span
          className="font-mono font-bold text-sm tabular-nums"
          style={{
            color: isPositive === false ? t.danger : t.success,
          }}
        >
          {pnl}
        </span>
        <span
          className="px-2.5 py-1 rounded-md text-[10px] font-bold tracking-wider uppercase transition-all"
          style={{
            background:
              mode === "LIVE"
                ? theme === "dark"
                  ? "rgba(16, 185, 129, 0.2)"
                  : "rgba(16, 185, 129, 0.15)"
                : theme === "dark"
                ? "rgba(107, 114, 128, 0.3)"
                : "rgba(107, 114, 128, 0.2)",
            color: mode === "LIVE" ? t.success : t.text.secondary,
          }}
        >
          {mode}
        </span>
      </div>
    </div>
  );
};

const MarketWatchItem: React.FC<{
  symbol: string;
  price?: string;
  change?: string;
  sparkline?: boolean;
  theme: Theme;
}> = ({ symbol, price, change, sparkline, theme }) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-lg p-3 mb-2 flex justify-between items-center border transition-all duration-200 hover:shadow-md group"
      style={{
        background: t.bg.secondary,
        borderColor: t.border,
      }}
    >
      <div>
        <div className="font-semibold text-sm" style={{ color: t.text.primary }}>
          {symbol}
        </div>
        {!sparkline && (
          <div className="text-[11px] mt-1" style={{ color: t.text.tertiary }}>
            {symbol === "Indices" ? "Stocks" : ""}
          </div>
        )}
      </div>
      {sparkline ? (
        <svg
          className="w-[60px] h-[24px] opacity-80 group-hover:opacity-100 transition-opacity"
          viewBox="0 0 60 24"
          preserveAspectRatio="none"
        >
          <polyline
            points="0,20 5,15 10,18 15,10 20,12 25,8 30,10 35,5 40,8 45,3 50,6 55,2 60,4"
            stroke={symbol === "FINNIFTY" ? t.danger : t.success}
            fill="none"
            strokeWidth="1.5"
          />
        </svg>
      ) : (
        <div className="text-right">
          <div className="font-bold text-sm" style={{ color: t.text.primary }}>
            {price}
          </div>
          <div className="text-[11px]" style={{ color: t.success }}>
            {change}
          </div>
        </div>
      )}
    </div>
  );
};

const PositionItem: React.FC<PositionItemProps> = ({
  symbol,
  quantity,
  price,
  change,
  isPositive,
  theme,
}) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-lg p-3 mb-2 flex justify-between items-center border transition-all duration-200 hover:shadow-md group"
      style={{
        background: t.bg.secondary,
        borderColor: t.border,
      }}
    >
      <div className="flex-1">
        <div className="font-semibold text-sm" style={{ color: t.text.primary }}>
          {symbol}
        </div>
        <div className="text-[11px] mt-1" style={{ color: t.text.tertiary }}>
          Qty {quantity}
        </div>
      </div>
      <div className="text-right">
        <div className="font-bold text-sm" style={{ color: t.text.primary }}>
          {price}
        </div>
        <div
          className="text-[11px]"
          style={{
            color: isPositive === false ? t.danger : t.success,
          }}
        >
          {change}
        </div>
      </div>
    </div>
  );
};

const OrderItem: React.FC<OrderItemProps> = ({
  symbol,
  side,
  time,
  status,
  theme,
}) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-lg p-3 mb-2 flex justify-between items-center border transition-all duration-200 hover:shadow-md"
      style={{
        background: t.bg.secondary,
        borderColor: t.border,
      }}
    >
      <div className="font-semibold text-sm" style={{ color: t.text.primary }}>
        {symbol}
      </div>
      <div className="flex gap-1.5 items-center text-[10px]">
        <span
          className="px-2 py-1 rounded-md font-bold tracking-wider uppercase"
          style={{
            background: side === "BUY" ? "rgba(16, 185, 129, 0.2)" : "rgba(239, 68, 68, 0.2)",
            color: side === "BUY" ? t.success : t.danger,
          }}
        >
          {side}
        </span>
        <span style={{ color: t.text.tertiary }}>{time}</span>
        <span
          className="px-2 py-1 rounded-md font-bold tracking-wider uppercase"
          style={{
            background: "rgba(16, 185, 129, 0.2)",
            color: t.success,
          }}
        >
          {status}
        </span>
      </div>
    </div>
  );
};

const NewsItem: React.FC<NewsItemProps & { theme: Theme }> = ({
  emoji,
  title,
  timeAgo,
  theme,
}) => {
  const t = themes[theme];
  return (
    <div
      className="rounded-lg p-3 mb-2 border transition-all duration-200 hover:shadow-md group cursor-pointer"
      style={{
        background: t.bg.secondary,
        borderColor: t.border,
      }}
    >
      <div className="font-semibold text-sm" style={{ color: t.text.primary }}>
        {emoji} {title}
      </div>
      <div className="text-[11px] mt-1" style={{ color: t.text.tertiary }}>
        {timeAgo}
      </div>
    </div>
  );
};

const SidebarItem: React.FC<SidebarItemProps> = ({ icon, label, active, theme }) => {
  const t = themes[theme];
  return (
    <div
      className={cn(
        "px-4 py-3 rounded-xl cursor-pointer text-sm flex items-center gap-3 mb-1 transition-all duration-200 font-semibold",
        active
          ? "shadow-lg"
          : "hover:shadow-md"
      )}
      style={{
        background: active
          ? theme === "dark"
            ? `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)`
            : `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)`
          : t.bg.secondary,
        color: active ? "#ffffff" : t.text.secondary,
        border: `1px solid ${active ? t.accent : t.border}`,
      }}
    >
      <span className="text-lg">{icon}</span>
      <span className="tracking-tight">{label}</span>
      {active && <ChevronRight size={16} className="ml-auto" />}
    </div>
  );
};

export const DashboardPageExact: React.FC = () => {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [theme, setTheme] = useState<Theme>("dark");
  const t = themes[theme];

  return (
    <>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap');

        :root {
          --font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          --font-mono: 'JetBrains Mono', 'Courier New', monospace;
        }

        body {
          transition: background-color 0.3s ease, color 0.3s ease;
        }

        .font-display {
          letter-spacing: -0.02em;
          font-weight: 700;
        }

        .font-mono {
          font-family: var(--font-mono);
          letter-spacing: 0;
        }

        .tabular-nums {
          font-variant-numeric: tabular-nums;
        }

        * {
          -webkit-font-smoothing: antialiased;
          -moz-osx-font-smoothing: grayscale;
        }

        /* Scrollbar styling */
        ::-webkit-scrollbar {
          width: 8px;
          height: 8px;
        }

        ::-webkit-scrollbar-track {
          background: transparent;
        }

        ::-webkit-scrollbar-thumb {
          border-radius: 4px;
          background: rgba(100, 116, 139, 0.5);
        }

        ::-webkit-scrollbar-thumb:hover {
          background: rgba(100, 116, 139, 0.7);
        }
      `}</style>

      <div
        className="w-full h-screen text-[#e0e0e0] overflow-hidden flex flex-col transition-colors duration-300"
        style={{
          background: t.bg.primary,
          color: t.text.primary,
          fontFamily: "var(--font-sans, 'Inter', system-ui)",
        }}
      >
        {/* NAVBAR */}
        <nav
          className="h-16 flex items-center px-6 gap-6 sticky top-0 z-100 border-b backdrop-blur-md transition-colors duration-300"
          style={{
            background:
              theme === "dark"
                ? "rgba(15, 20, 25, 0.95)"
                : "rgba(255, 255, 255, 0.95)",
            borderColor: t.border,
          }}
        >
          <div className="text-xl font-bold flex items-center gap-2 tracking-tight" style={{ color: t.text.primary }}>
            💎 <span className="font-display">Stokr</span>
          </div>

          <button
            className="w-10 h-10 rounded-lg border transition-all duration-200"
            style={{
              background: t.bg.secondary,
              borderColor: t.border,
              color: t.text.primary,
            }}
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
          >
            <Menu size={18} className="mx-auto" />
          </button>

          <div className="flex-0.4 relative">
            <Search
              size={16}
              className="absolute left-3 top-1/2 transform -translate-y-1/2 transition-colors"
              style={{ color: t.text.tertiary }}
            />
            <input
              type="text"
              placeholder="Search markets, strategies..."
              className="w-full py-2.5 pl-10 pr-4 rounded-lg border transition-all duration-200 focus:outline-none focus:ring-2"
              style={{
                background: t.bg.secondary,
                borderColor: t.border,
                color: t.text.primary,
              }}
            />
          </div>

          <div className="flex gap-4 flex-1">
            {[
              { label: "NIFTY", price: "24,325.10", change: "▲ 0.72%" },
              { label: "BANKNIFTY", price: "52,963.40", change: "▲ 1.21%" },
              { label: "SENSEX", price: "80,123.42", change: "▲ 0.68%" },
            ].map((ticker, idx) => (
              <div key={idx} className="flex gap-2 items-center text-sm">
                <span style={{ color: t.text.tertiary }} className="font-semibold tracking-widest uppercase text-xs">
                  {ticker.label}
                </span>
                <span className="font-mono font-bold text-sm tabular-nums" style={{ color: t.text.primary }}>
                  {ticker.price}
                </span>
                <span className="text-xs font-semibold" style={{ color: t.success }}>
                  {ticker.change}
                </span>
              </div>
            ))}
          </div>

          <div className="flex gap-3 items-center ml-auto">
            <button
              className="w-10 h-10 rounded-lg border transition-all duration-200 flex items-center justify-center"
              style={{
                background: t.bg.secondary,
                borderColor: t.border,
                color: t.text.primary,
              }}
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            >
              {theme === "dark" ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <button
              className="w-10 h-10 rounded-lg border transition-all duration-200 flex items-center justify-center hover:shadow-md"
              style={{
                background: t.bg.secondary,
                borderColor: t.border,
                color: t.text.primary,
              }}
            >
              <Bell size={18} />
            </button>
            <button
              className="w-10 h-10 rounded-lg border transition-all duration-200 flex items-center justify-center hover:shadow-md"
              style={{
                background: t.bg.secondary,
                borderColor: t.border,
                color: t.text.primary,
              }}
            >
              <Heart size={18} />
            </button>
            <button
              className="w-10 h-10 rounded-full font-bold text-sm flex items-center justify-center shadow-lg transition-all duration-200 hover:shadow-xl hover:scale-105"
              style={{
                background: `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)`,
                color: "#ffffff",
              }}
            >
              R
            </button>
            <button
              className="w-10 h-10 rounded-lg border transition-all duration-200 flex items-center justify-center"
              style={{
                background: t.bg.secondary,
                borderColor: t.border,
                color: t.text.primary,
              }}
            >
              <MoreVertical size={18} />
            </button>
          </div>
        </nav>

        {/* MAIN CONTAINER */}
        <div className="flex flex-1 overflow-hidden">
          {/* SIDEBAR */}
          <aside
            className="w-48 p-4 overflow-y-auto flex flex-col border-r transition-colors duration-300"
            style={{
              background: theme === "dark" ? "rgba(15, 20, 25, 0.5)" : "rgba(249, 250, 251, 0.5)",
              borderColor: t.border,
            }}
          >
            <div className="mb-6">
              <SidebarItem icon="📊" label="Dashboard" active theme={theme} />
              <SidebarItem icon="🎯" label="Strategies" theme={theme} />
              <SidebarItem icon="💼" label="Positions" theme={theme} />
              <SidebarItem icon="📋" label="Orders" theme={theme} />
              <SidebarItem icon="⚡" label="Executions" theme={theme} />
              <SidebarItem icon="📈" label="Watchlist" theme={theme} />
              <SidebarItem icon="📚" label="Backtests" theme={theme} />
            </div>

            <div className="mb-6">
              <div className="text-[10px] font-bold tracking-widest uppercase mb-3 px-2" style={{ color: t.text.tertiary }}>
                Integrations
              </div>
              <SidebarItem icon="🔗" label="Broker Connect" theme={theme} />
              <SidebarItem icon="🔔" label="Alerts" theme={theme} />
            </div>

            <div>
              <div className="text-[10px] font-bold tracking-widest uppercase mb-3 px-2" style={{ color: t.text.tertiary }}>
                Account
              </div>
              <SidebarItem icon="⚙️" label="Settings" theme={theme} />
            </div>
          </aside>

          {/* MAIN CONTENT */}
          <div className="flex-1 grid grid-cols-[1fr_380px] gap-6 p-8 overflow-y-auto transition-colors duration-300" style={{ background: t.bg.primary }}>
            {/* CENTER CONTENT */}
            <div className="flex flex-col gap-6">
              {/* HEADER */}
              <div className="flex justify-between items-center mb-2">
                <div>
                  <h1 className="text-5xl font-bold tracking-tight mb-2" style={{ color: t.text.primary }}>
                    Good morning, <span className="font-display">Rohan</span> 👋
                  </h1>
                  <p className="text-sm font-light tracking-wide" style={{ color: t.text.tertiary }}>
                    Here's your trading overview
                  </p>
                </div>
                <div className="flex gap-2">
                  {["PAPER", "● LIVE", "Customize"].map((btn, idx) => (
                    <button
                      key={idx}
                      className="px-5 py-2.5 rounded-lg border font-semibold text-sm transition-all duration-200 hover:shadow-md"
                      style={{
                        background: idx === 1 ? `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)` : t.bg.secondary,
                        borderColor: idx === 1 ? t.accent : t.border,
                        color: idx === 1 ? "#ffffff" : t.text.primary,
                      }}
                    >
                      {btn}
                    </button>
                  ))}
                </div>
              </div>

              {/* STATS GRID */}
              <div className="grid grid-cols-5 gap-4">
                {[
                  { label: "Total P&L", value: "₹ 1,24,567.90", change: "▲ 2.45% Today", pos: true },
                  { label: "Unrealized P&L", value: "₹ 45,230.00", change: "▲ 1.12%", pos: true },
                  { label: "Realized P&L", value: "₹ 79,337.90", change: "▲ 3.21%", pos: true },
                  { label: "Total Capital", value: "₹ 12,45,000.00", change: "Invested", pos: true },
                  { label: "Risk Utilization", value: "42%", change: "Within Limits", pos: true },
                ].map((stat, idx) => (
                  <StatCard key={idx} theme={theme} {...stat} isPositive={stat.pos} />
                ))}
              </div>

              {/* CHART CARD */}
              <div
                className="rounded-2xl p-6 flex-1 flex flex-col border transition-all duration-300"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-start mb-4 pb-4 border-b" style={{ borderColor: t.border }}>
                  <div>
                    <h3 className="text-lg font-semibold tracking-tight" style={{ color: t.text.primary }}>
                      NIFTY 50 <span className="font-light text-sm" style={{ color: t.text.tertiary }}>• 5m • NSE</span>
                    </h3>
                    <p className="text-xs mt-2 font-light tracking-tight" style={{ color: t.text.tertiary }}>
                      <span className="font-mono font-semibold" style={{ color: t.text.primary }}>
                        24,325.10
                      </span>{" "}
                      <span style={{ color: t.success }}>▲ 173.20</span> <span style={{ color: t.success }}>(0.72%)</span>
                    </p>
                  </div>
                  <div className="flex gap-1">
                    {["1m", "5m", "15m", "1h", "4h"].map((tf) => (
                      <button
                        key={tf}
                        className={cn(
                          "px-3 py-2 rounded-lg border text-xs font-semibold transition-all",
                          tf === "15m" ? "shadow-md" : "hover:shadow-md"
                        )}
                        style={{
                          background: tf === "15m" ? `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)` : t.bg.secondary,
                          borderColor: tf === "15m" ? t.accent : t.border,
                          color: tf === "15m" ? "#ffffff" : t.text.secondary,
                        }}
                      >
                        {tf}
                      </button>
                    ))}
                  </div>
                </div>

                {/* CANDLESTICKS */}
                <div
                  className="h-72 rounded-xl mb-4 p-4 flex items-end justify-around gap-1"
                  style={{
                    background: theme === "dark" ? "rgba(255, 255, 255, 0.02)" : "rgba(0, 0, 0, 0.02)",
                  }}
                >
                  {[
                    { height: 35, down: false },
                    { height: 52, down: false },
                    { height: 42, down: true },
                    { height: 68, down: false },
                    { height: 58, down: false },
                    { height: 72, down: false },
                    { height: 85, down: false },
                    { height: 65, down: false },
                    { height: 78, down: false },
                  ].map((candle, idx) => (
                    <div key={idx} className="flex-1 flex flex-col items-center justify-end">
                      <div
                        className="w-full rounded-sm transition-all duration-300 hover:shadow-lg"
                        style={{
                          height: `${candle.height}%`,
                          background: candle.down
                            ? `linear-gradient(180deg, ${t.danger} 0%, ${theme === "dark" ? "#991b1b" : "#dc2626"} 100%)`
                            : `linear-gradient(180deg, ${t.success} 0%, ${theme === "dark" ? "#065f46" : "#059669"} 100%)`,
                          boxShadow: "0 2px 8px rgba(0, 0, 0, 0.1)",
                        }}
                      />
                    </div>
                  ))}
                </div>

                {/* CHART INFO */}
                <div className="grid grid-cols-4 gap-3">
                  {[
                    { label: "Open", value: "24,215.30" },
                    { label: "High", value: "24,365.80" },
                    { label: "Low", value: "24,210.50" },
                    { label: "Volume", value: "2.4M" },
                  ].map((info, idx) => (
                    <div
                      key={idx}
                      className="rounded-lg p-3 border transition-all duration-200 hover:shadow-md"
                      style={{
                        background: t.bg.secondary,
                        borderColor: t.border,
                      }}
                    >
                      <div className="text-[10px] font-bold tracking-wider uppercase mb-1" style={{ color: t.text.tertiary }}>
                        {info.label}
                      </div>
                      <div className="font-mono font-bold text-sm" style={{ color: t.text.primary }}>
                        {info.value}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* RIGHT SIDEBAR */}
            <div className="flex flex-col gap-4 overflow-y-auto">
              {/* Active Strategies */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    Active Strategies
                  </h4>
                  <a href="#" className="text-xs font-bold transition-colors hover:opacity-80" style={{ color: t.accent }}>
                    View all
                  </a>
                </div>
                {[
                  { name: "Trend Hunter Pro", pnl: "▲ 45,270", mode: "LIVE" as const, pos: true },
                  { name: "Mean Reversion X", pnl: "▼ 1,320", mode: "LIVE" as const, pos: false },
                  { name: "Breakout Alpha", pnl: "▲ 12,450", mode: "LIVE" as const, pos: true },
                  { name: "VWAP Scalper", pnl: "▼ 2,120", mode: "PAPER" as const, pos: false },
                ].map((item, idx) => (
                  <StrategyItem key={idx} theme={theme} {...item} isPositive={item.pos} />
                ))}
              </div>

              {/* Market Watch */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    Market Watch
                  </h4>
                  <a href="#" className="text-xs font-bold transition-colors hover:opacity-80" style={{ color: t.accent }}>
                    +
                  </a>
                </div>
                {[
                  { symbol: "Indices", price: "24,325.10", change: "▲ +0.72%", sparkline: false },
                  { symbol: "NIFTY 50", sparkline: true },
                  { symbol: "BANKNIFTY", sparkline: true },
                  { symbol: "SENSEX", sparkline: true },
                  { symbol: "FINNIFTY", sparkline: true },
                ].map((item, idx) => (
                  <MarketWatchItem key={idx} theme={theme} {...item} />
                ))}
              </div>

              {/* Positions */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    Positions
                  </h4>
                  <a href="#" className="text-xs font-bold transition-colors hover:opacity-80" style={{ color: t.accent }}>
                    View all
                  </a>
                </div>
                {[
                  { symbol: "RELIANCE", quantity: "10", price: "₹ 2,449.00", change: "▲ +1.25%", pos: true },
                  { symbol: "INFY", quantity: "5", price: "₹ 1,450.00", change: "▼ −0.35%", pos: false },
                  { symbol: "TCS", quantity: "8", price: "₹ 3,950.00", change: "▼ −0.75%", pos: false },
                ].map((item, idx) => (
                  <PositionItem key={idx} theme={theme} {...item} isPositive={item.pos} />
                ))}
              </div>

              {/* Recent Orders */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    Recent Orders
                  </h4>
                  <a href="#" className="text-xs font-bold transition-colors hover:opacity-80" style={{ color: t.accent }}>
                    View all
                  </a>
                </div>
                {[
                  { symbol: "RELIANCE", side: "BUY" as const, time: "10:29:35", status: "FILLED" as const },
                  { symbol: "INFY", side: "SELL" as const, time: "10:28:11", status: "FILLED" as const },
                  { symbol: "TCS", side: "BUY" as const, time: "10:27:02", status: "PENDING" as const },
                ].map((item, idx) => (
                  <OrderItem key={idx} theme={theme} {...item} />
                ))}
              </div>

              {/* News & Alerts */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    News & Alerts
                  </h4>
                  <a href="#" className="text-xs font-bold transition-colors hover:opacity-80" style={{ color: t.accent }}>
                    View all
                  </a>
                </div>
                {[
                  { emoji: "🔴", title: "High volatility in NIFTY options", timeAgo: "2m ago" },
                  { emoji: "🟠", title: "Breakout Alpha hit TARGET", timeAgo: "5m ago" },
                  { emoji: "🔵", title: "INFY options flow spike", timeAgo: "7m ago" },
                  { emoji: "🟢", title: "Market opening range identified", timeAgo: "10m ago" },
                ].map((item, idx) => (
                  <NewsItem key={idx} theme={theme} {...item} />
                ))}
              </div>

              {/* AI Insights */}
              <div
                className="rounded-2xl p-5 border transition-all duration-300 hover:shadow-lg"
                style={{
                  background:
                    theme === "dark"
                      ? `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`
                      : `linear-gradient(135deg, ${t.bg.tertiary} 0%, ${t.bg.secondary} 100%)`,
                  borderColor: t.border,
                }}
              >
                <div className="flex justify-between items-center mb-3 pb-3 border-b" style={{ borderColor: t.border }}>
                  <h4 className="text-sm font-semibold tracking-tight" style={{ color: t.text.primary }}>
                    🤖 AI Insights
                  </h4>
                  <span
                    className="px-2 py-1 rounded-md text-[9px] font-bold tracking-wider uppercase"
                    style={{
                      background: `linear-gradient(135deg, ${t.accent} 0%, ${t.accentLight} 100%)`,
                      color: "#ffffff",
                    }}
                  >
                    BETA
                  </span>
                </div>
                <div
                  className="p-4 rounded-lg text-xs leading-relaxed transition-all duration-200 hover:shadow-md"
                  style={{
                    background: t.bg.secondary,
                    color: t.text.secondary,
                  }}
                >
                  <strong style={{ color: t.text.primary }}>NIFTY showing strong momentum</strong>
                  <br />
                  Potential breakout above 24,400
                  <br />
                  <br />
                  <button
                    className="w-full px-4 py-2 rounded-lg border font-semibold text-xs transition-all duration-200 hover:shadow-md"
                    style={{
                      background: "transparent",
                      borderColor: t.border,
                      color: t.text.secondary,
                    }}
                  >
                    View Analysis
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
};
