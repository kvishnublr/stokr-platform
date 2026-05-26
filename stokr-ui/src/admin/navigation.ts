import type { LucideIcon } from "lucide-react";
import {
  Activity,
  AlertOctagon,
  BarChart3,
  Bell,
  Building2,
  ClipboardCheck,
  Cpu,
  Database,
  FileBarChart,
  Gauge,
  LayoutDashboard,
  Radar,
  Radio,
  RotateCcw,
  Server,
  Settings,
  Settings2,
  Shield,
  ShieldCheck,
  SlidersHorizontal,
  Stethoscope,
  Terminal,
  TrendingUp,
  Users,
  Workflow,
  Zap,
} from "lucide-react";

export type AdminNavTier = "critical" | "frequent" | "secondary" | "rare";

export type AdminNavItem = {
  to: string;
  label: string;
  description: string;
  icon: LucideIcon;
  tier: AdminNavTier;
  end?: boolean;
  matchPrefix?: string;
  keywords?: string[];
  deprecated?: boolean;
};

export type AdminNavGroup = {
  id: string;
  title: string;
  subtitle: string;
  items: AdminNavItem[];
};

/** Institutional admin information architecture — 12 operational groups. */
export const ADMIN_NAV_GROUPS: AdminNavGroup[] = [
  {
    id: "command",
    title: "Command Center",
    subtitle: "War room & platform pulse",
    items: [
      {
        to: "/admin",
        end: true,
        label: "Command Center",
        description: "Live operations war room — PnL, health, alerts, exposure",
        icon: LayoutDashboard,
        tier: "critical",
        keywords: ["home", "overview", "dashboard", "war room"],
      },
      {
        to: "/admin/overview",
        label: "Platform Overview",
        description: "Readiness banners, broker controls, pipeline telemetry",
        icon: Gauge,
        tier: "frequent",
        keywords: ["readiness", "telemetry"],
      },
      {
        to: "/admin/pipeline-health",
        label: "Pipeline Health",
        description: "End-to-end feed → signal → OMS cascade",
        icon: Workflow,
        tier: "critical",
        keywords: ["pipeline", "cascade", "health"],
      },
    ],
  },
  {
    id: "trading-ops",
    title: "Trading Operations",
    subtitle: "Live desk & intraday",
    items: [
      {
        to: "/admin/oms",
        label: "OMS Monitor",
        description: "Order grid, execution events, cancel actions",
        icon: BarChart3,
        tier: "critical",
        keywords: ["orders", "oms"],
      },
      {
        to: "/admin/intraday",
        matchPrefix: "/admin/intraday",
        label: "Intraday Ops",
        description: "Setup readiness, symbol coverage, strategy audit",
        icon: Radar,
        tier: "frequent",
        keywords: ["intraday", "setups"],
      },
      {
        to: "/admin/traders-health",
        label: "Trader Health",
        description: "Per-trader execution health from ops snapshot",
        icon: Stethoscope,
        tier: "frequent",
        keywords: ["traders", "health"],
      },
      {
        to: "/admin/ops",
        matchPrefix: "/admin/ops",
        label: "Execution Controls",
        description: "Kill switch, replay controls, readiness checks",
        icon: Cpu,
        tier: "critical",
        keywords: ["kill", "emergency", "controls"],
      },
    ],
  },
  {
    id: "strategies-signals",
    title: "Strategies & Signals",
    subtitle: "Signal intelligence pipeline",
    items: [
      {
        to: "/admin/signals",
        label: "Signal Monitor",
        description: "Live signal stream, filters, SSE updates",
        icon: Zap,
        tier: "critical",
        keywords: ["signals", "live", "stream"],
      },
      {
        to: "/admin/signal-lab",
        label: "Signal Lab",
        description: "Stats, benchmarking, cleanup, outcome reruns",
        icon: BarChart3,
        tier: "frequent",
        keywords: ["lab", "benchmark", "cleanup"],
      },
      {
        to: "/admin/signal-replay",
        label: "Signal Replay",
        description: "Historical signal replay sessions",
        icon: RotateCcw,
        tier: "secondary",
        keywords: ["replay", "historical"],
      },
      {
        to: "/admin/strategy-catalog",
        label: "Strategy Catalog",
        description: "Create and manage strategy definitions",
        icon: Settings2,
        tier: "frequent",
        keywords: ["catalog", "definitions"],
      },
      {
        to: "/admin/strategies",
        label: "Strategy Control",
        description: "Visibility, risk level, runtime bindings",
        icon: SlidersHorizontal,
        tier: "frequent",
        keywords: ["manage", "strategies"],
      },
      {
        to: "/admin/universe-groups",
        label: "Universe Groups",
        description: "Symbol universes and import/sync",
        icon: Database,
        tier: "frequent",
        keywords: ["universe", "symbols"],
      },
      {
        to: "/admin/runtime-bindings",
        label: "Runtime Bindings",
        description: "Bind strategies to universes for scanning",
        icon: Radar,
        tier: "frequent",
        keywords: ["bindings", "runtime"],
      },
    ],
  },
  {
    id: "execution-oms",
    title: "Execution & OMS",
    subtitle: "Guard rails & test harness",
    items: [
      {
        to: "/admin/execution",
        label: "Execution Guard",
        description: "Entry/exit rules, OMS latency, timelines",
        icon: ShieldCheck,
        tier: "frequent",
        keywords: ["guard", "policies"],
      },
      {
        to: "/admin/execution-config",
        label: "Execution Config",
        description: "Per-strategy PAPER/LIVE, capital, qty limits",
        icon: SlidersHorizontal,
        tier: "frequent",
        keywords: ["config", "paper", "live"],
      },
      {
        to: "/admin/test-signal-lab",
        label: "Test Signal Lab",
        description: "End-to-end test signal path verification",
        icon: Zap,
        tier: "rare",
        keywords: ["test", "e2e"],
      },
      {
        to: "/admin/test-execution-monitor",
        label: "Test Monitor",
        description: "Telemetry for recent test signal runs",
        icon: Activity,
        tier: "rare",
        keywords: ["test", "monitor"],
      },
      {
        to: "/admin/failure-analysis",
        label: "Failure Analysis",
        description: "Plain-English diagnostics from test runs",
        icon: AlertOctagon,
        tier: "rare",
        keywords: ["failure", "diagnostics"],
      },
    ],
  },
  {
    id: "market-intel",
    title: "Market Intelligence",
    subtitle: "Tape, freshness, backfill",
    items: [
      {
        to: "/admin/market",
        label: "Market Feed",
        description: "Live infra, broker control, freshness grid",
        icon: Radio,
        tier: "frequent",
        keywords: ["market", "feed", "freshness"],
      },
      {
        to: "/admin/backfill",
        label: "Market Backfill",
        description: "Coverage tables, gap repair jobs",
        icon: Database,
        tier: "secondary",
        keywords: ["backfill", "coverage"],
      },
    ],
  },
  {
    id: "broker-infra",
    title: "Broker Infrastructure",
    subtitle: "Sessions, OAuth, websocket",
    items: [
      {
        to: "/admin/broker-infrastructure",
        label: "Broker Infrastructure",
        description: "Per-vendor broker state, OAuth, websocket heartbeat",
        icon: Building2,
        tier: "critical",
        keywords: ["broker", "zerodha", "oauth"],
      },
    ],
  },
  {
    id: "risk-exposure",
    title: "Risk & Exposure",
    subtitle: "Limits, capital, kill switches",
    items: [
      {
        to: "/admin/risk-dashboard",
        label: "Risk Terminal",
        description: "Strategy risk state, emergency stops, PnL vs limits",
        icon: ShieldCheck,
        tier: "critical",
        keywords: ["risk", "exposure", "drawdown"],
      },
      {
        to: "/admin/capital",
        label: "Capital Allocation",
        description: "Global and per-strategy capital utilization",
        icon: TrendingUp,
        tier: "secondary",
        keywords: ["capital", "allocation"],
      },
    ],
  },
  {
    id: "analytics",
    title: "Analytics & Performance",
    subtitle: "Reports & operational KPIs",
    items: [
      {
        to: "/admin/reports",
        label: "Reports",
        description: "Operational reports summary",
        icon: FileBarChart,
        tier: "secondary",
        keywords: ["reports", "analytics"],
      },
    ],
  },
  {
    id: "users-accounts",
    title: "Users & Accounts",
    subtitle: "Traders and approvals",
    items: [
      {
        to: "/admin/users",
        label: "Traders",
        description: "User CRUD, live-trading approval, password reset",
        icon: Users,
        tier: "critical",
        keywords: ["users", "traders", "accounts"],
      },
    ],
  },
  {
    id: "infrastructure",
    title: "Infrastructure & System Health",
    subtitle: "Queues, logs, replay, JVM",
    items: [
      {
        to: "/admin/infrastructure",
        label: "Infrastructure",
        description: "Queue depth, projection health, JVM metrics",
        icon: Server,
        tier: "secondary",
        keywords: ["queues", "jvm"],
      },
      {
        to: "/admin/infra-health-center",
        label: "Health Center",
        description: "Raw health cards — system, broker, OMS",
        icon: Gauge,
        tier: "secondary",
        keywords: ["health", "probes"],
      },
      {
        to: "/admin/replay",
        label: "Replay Infrastructure",
        description: "Replay queue and job telemetry",
        icon: RotateCcw,
        tier: "secondary",
        keywords: ["replay", "queue"],
      },
      {
        to: "/admin/logs",
        label: "Live Logs",
        description: "Streaming log viewer with filters",
        icon: Terminal,
        tier: "secondary",
        keywords: ["logs", "stream"],
      },
    ],
  },
  {
    id: "audit-compliance",
    title: "Audit & Compliance",
    subtitle: "Incidents, trails, governance",
    items: [
      {
        to: "/admin/history",
        label: "Incidents",
        description: "Operational history and incident strip",
        icon: AlertOctagon,
        tier: "secondary",
        keywords: ["incidents", "history"],
      },
      {
        to: "/admin/audit",
        label: "Audit Trail",
        description: "Broker actions, feed pauses, governance",
        icon: ClipboardCheck,
        tier: "secondary",
        keywords: ["audit", "compliance"],
      },
    ],
  },
  {
    id: "settings",
    title: "Settings & Controls",
    subtitle: "Platform configuration",
    items: [
      {
        to: "/admin/settings",
        label: "Settings",
        description: "Platform settings summary",
        icon: Settings,
        tier: "rare",
        keywords: ["settings", "config"],
      },
      {
        to: "/admin/security",
        label: "Security",
        description: "Security posture summary",
        icon: Shield,
        tier: "rare",
        keywords: ["security", "auth"],
      },
      {
        to: "/admin/alerts",
        label: "Alert Center",
        description: "Active alerts and acknowledgement workflow",
        icon: Bell,
        tier: "frequent",
        keywords: ["alerts", "notifications"],
      },
    ],
  },
];

export const ADMIN_FLAT_NAV: AdminNavItem[] = ADMIN_NAV_GROUPS.flatMap((g) => g.items);

export function findAdminNavItem(pathname: string): AdminNavItem | undefined {
  const normalized = pathname.replace(/\/+$/, "") || "/admin";
  let best: AdminNavItem | undefined;
  let bestLen = -1;
  for (const item of ADMIN_FLAT_NAV) {
    const base = item.to.replace(/\/+$/, "");
    const matches =
      item.end === true
        ? normalized === base
        : normalized === base || normalized.startsWith(`${base}/`);
    if (matches && base.length > bestLen) {
      best = item;
      bestLen = base.length;
    }
  }
  return best;
}

export function findAdminNavGroup(pathname: string): AdminNavGroup | undefined {
  const item = findAdminNavItem(pathname);
  if (!item) return undefined;
  return ADMIN_NAV_GROUPS.find((g) => g.items.some((i) => i.to === item.to));
}

export function adminBreadcrumbs(pathname: string): Array<{ label: string; to?: string }> {
  const group = findAdminNavGroup(pathname);
  const item = findAdminNavItem(pathname);
  const crumbs: Array<{ label: string; to?: string }> = [{ label: "Admin", to: "/admin" }];
  if (group && group.id !== "command") {
    crumbs.push({ label: group.title });
  }
  if (item && item.to !== "/admin") {
    crumbs.push({ label: item.label, to: item.to });
  }
  return crumbs;
}
