import { cn } from "../../lib/utils";
import type { AdminDashboardData } from "../../services/dashboard/types";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

const nav = ["Dashboard", "Users", "Analytics", "Backtests", "Settings", "Security", "Reports", "Alerts"];

export function AdminSidebar() {
  const navigate = useNavigate();
  const handleNav = (n: string) => {
    if (n === "Dashboard") return navigate("/admin");
    if (n === "Users") return navigate("/admin/users");
    if (n === "Analytics") return navigate("/admin/oms");
    if (n === "Backtests") return navigate("/backtests/launch");
    if (n === "Settings") return navigate("/admin/settings");
    if (n === "Security") return navigate("/admin/security");
    if (n === "Reports") return navigate("/admin/reports");
    if (n === "Alerts") return navigate("/admin/alerts");
    toast.info("Navigation unavailable.");
  };

  return (
    <div className="h-full w-52 overflow-y-auto border-r border-gray-200 bg-white">
      <div className="p-5">
        <div className="mb-8 flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded bg-orange-500 font-bold text-white">S</div>
          <span className="text-lg font-bold">Stokr Admin</span>
        </div>
        <div className="mb-8 space-y-2">
          <div className="mb-3 px-3 text-xs font-semibold uppercase tracking-wide text-gray-500">Main</div>
          {nav.map((n, idx) => (
            <div
              key={n}
              onClick={() => handleNav(n)}
              className={cn("rounded-lg px-4 py-3 text-sm", idx === 0 ? "bg-orange-50 font-medium text-orange-500" : "cursor-pointer text-gray-600 hover:bg-gray-50")}
            >
              {n}
            </div>
          ))}
        </div>
        <div className="rounded-lg bg-gray-50 p-4">
          <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">Admin Account</div>
          <div className="mt-3 text-sm font-bold text-gray-900">Super Admin</div>
        </div>
      </div>
    </div>
  );
}

export function AdminTopbar({
  onRefresh,
  search,
  onSearchChange,
  onLogout,
}: {
  onRefresh: () => void;
  search: string;
  onSearchChange: (value: string) => void;
  onLogout: () => void;
}) {
  return (
    <div className="flex items-center gap-5 border-b border-gray-200 bg-white px-6 py-3.5">
        <button className="text-gray-600 hover:text-gray-900" onClick={() => toast.info("Sidebar collapse for admin dashboard is coming soon.")}>☰</button>
      <div className="relative max-w-sm flex-1">
        <input
          type="text"
          placeholder="Search users, alerts, audit..."
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          className="w-full rounded-lg border border-gray-200 bg-gray-50 px-4 py-2 text-sm focus:border-orange-500 focus:outline-none"
        />
      </div>
      <div className="ml-auto flex items-center gap-4">
          <button className="rounded border border-gray-300 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50" onClick={onRefresh}>Refresh</button>
        <div className="relative">
          <span className="absolute -right-2 -top-2 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-xs font-bold text-white">3</span>
            <button className="text-xl text-gray-600" onClick={() => toast.info("Opening alerts center…", { description: "Use Admin > Dashboard alerts panel for live incidents." })}>🔔</button>
        </div>
        <button className="rounded border border-gray-300 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50" onClick={onLogout}>Sign out</button>
        <div className="flex h-10 w-10 items-center justify-center rounded-full bg-blue-400 font-bold text-white">A</div>
      </div>
    </div>
  );
}

export function AdminMain({ data }: { data: AdminDashboardData }) {
  const navigate = useNavigate();
  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Admin Dashboard</h1>
          <p className="mt-1 text-sm text-gray-500">System overview and management</p>
        </div>
        <div className="hidden gap-2 md:flex">
          <button className="rounded border border-gray-300 px-3 py-2 text-xs font-semibold text-gray-700 hover:bg-gray-50" onClick={() => navigate("/admin/users")}>Users</button>
          <button className="rounded border border-gray-300 px-3 py-2 text-xs font-semibold text-gray-700 hover:bg-gray-50" onClick={() => navigate("/admin/oms")}>OMS</button>
          <button className="rounded border border-gray-300 px-3 py-2 text-xs font-semibold text-gray-700 hover:bg-gray-50" onClick={() => navigate("/backtests/launch")}>Backtests</button>
          <button className="rounded bg-orange-500 px-3 py-2 text-xs font-semibold text-white hover:opacity-90" onClick={() => navigate("/admin/ops")}>Risk</button>
        </div>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
        {data.userMetrics.map((s) => (
          <div key={s.label} className="rounded-xl border border-gray-200 bg-white p-5">
            <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">{s.label}</div>
            <div className="mt-2 text-2xl font-bold text-gray-900">{s.value}</div>
            <div className={cn("mt-2 text-xs font-medium", s.positive ? "text-green-600" : "text-red-600")}>{s.delta}</div>
          </div>
        ))}
      </div>
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <section className="rounded-2xl border border-gray-200 bg-white p-6">
          <h3 className="mb-4 text-lg font-bold text-gray-900">Runtime Metrics</h3>
          <div className="space-y-3 text-sm">
            {data.runtimeMetrics.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
            {data.runtimeMetrics.map((m) => (
              <div key={m.label} className="flex justify-between rounded-lg bg-gray-50 p-3">
                <span className="text-gray-600">{m.label}</span>
                <span className="font-bold text-gray-900">{m.value}</span>
              </div>
            ))}
          </div>
        </section>
        <section className="rounded-2xl border border-gray-200 bg-white p-6">
          <h3 className="mb-4 text-lg font-bold text-gray-900">Risk & Broker Health</h3>
          <div className="space-y-2">
            {data.riskMetrics.length === 0 && data.brokerHealth.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
            {data.riskMetrics.map((r) => (
              <div key={r.label} className="flex justify-between rounded-lg bg-gray-50 p-3 text-sm">
                <span className="text-gray-600">{r.label}</span>
                <span className={cn("font-bold", r.level === "critical" ? "text-red-600" : r.level === "warn" ? "text-amber-600" : "text-green-600")}>{r.value}</span>
              </div>
            ))}
            {data.brokerHealth.map((b) => (
              <div key={b.name} className="flex justify-between rounded-lg bg-gray-50 p-3 text-sm">
                <span className="text-gray-600">{b.name}</span>
                <span className={cn("font-bold", b.status === "DOWN" ? "text-red-600" : b.status === "WARNING" ? "text-amber-600" : "text-green-600")}>{b.status}</span>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

export function AdminRightRail({ data }: { data: AdminDashboardData }) {
  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-4 border-b border-gray-200 pb-3 font-bold text-gray-900">Live Trader Activity</h3>
        <div className="space-y-3">
          {data.liveTraderActivity.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
          {data.liveTraderActivity.map((u) => (
            <div key={u.name} className="flex items-center justify-between rounded-lg bg-gray-50 p-3">
              <div>
                <div className="text-sm font-semibold text-gray-900">{u.name}</div>
                <div className="text-xs text-gray-500">{u.tier}</div>
              </div>
              <span className={cn("h-2 w-2 rounded-full", u.online ? "bg-green-500" : "bg-gray-400")} />
            </div>
          ))}
        </div>
      </section>
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-4 border-b border-gray-200 pb-3 font-bold text-gray-900">Alerts & Notifications</h3>
        <div className="space-y-3">
          {data.alerts.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
          {data.alerts.map((a) => (
            <div key={a.title} className={cn("rounded-lg border p-3", a.level === "critical" && "border-red-100 bg-red-50", a.level === "warn" && "border-yellow-100 bg-yellow-50", a.level === "info" && "border-blue-100 bg-blue-50")}>
              <div className="text-xs font-semibold text-gray-900">{a.title}</div>
              <div className="mt-1 text-xs text-gray-600">{a.detail}</div>
            </div>
          ))}
        </div>
      </section>
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-3 font-bold text-gray-900">Audit Activity</h3>
        <div className="space-y-2 text-xs">
          {data.auditActivity.length === 0 ? <div className="rounded bg-gray-50 p-2 text-gray-500">No data available.</div> : null}
          {data.auditActivity.map((a) => (
            <div key={`${a.time}-${a.action}`} className="rounded bg-gray-50 p-2">
              <div className="font-medium text-gray-900">{a.action}</div>
              <div className="mt-1 text-gray-500">{a.time} · {a.actor}</div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
