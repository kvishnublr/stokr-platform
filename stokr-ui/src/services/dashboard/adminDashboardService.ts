import { api } from "../../api/client";
import type { AdminDashboardData } from "./types";

const EMPTY_ADMIN_DASHBOARD: AdminDashboardData = {
  userMetrics: [
    { label: "Total Users", value: "-", delta: "No data available", positive: false },
    { label: "Active Sessions", value: "-", delta: "No data available", positive: false },
    { label: "Total Transactions", value: "-", delta: "No data available", positive: false },
    { label: "System Health", value: "-", delta: "No data available", positive: false },
  ],
  runtimeMetrics: [],
  riskMetrics: [],
  brokerHealth: [],
  replayMetrics: [],
  omsStats: [],
  websocketMetrics: [],
  auditActivity: [],
  liveTraderActivity: [],
  alerts: [],
};

function str(value: unknown, fallback: string): string {
  if (typeof value === "string" && value.length) return value;
  if (typeof value === "number") return String(value);
  return fallback;
}

export async function fetchAdminDashboardData(): Promise<AdminDashboardData> {
  const requests = await Promise.allSettled([
    api.get("/api/admin/users?page=0&size=50"),
    api.get("/api/admin/health"),
    api.get("/api/admin/ops/status"),
    api.get("/api/admin/readiness"),
    api.get("/api/admin/oms/summary"),
    api.get("/api/admin/audit?page=0&size=20"),
    api.get("/api/admin/alerts"),
  ]);

  const merged: AdminDashboardData = {
    ...EMPTY_ADMIN_DASHBOARD,
    userMetrics: [...EMPTY_ADMIN_DASHBOARD.userMetrics],
  };
  const [users, health, ops, readiness, oms, audit, alerts] = requests;

  if (users.status === "fulfilled") {
    const d = users.value.data?.data;
    const content = Array.isArray(d?.content) ? d.content : [];
    if (d != null) {
      const totalUsers = Number(d.totalElements ?? content.length ?? 0);
      const activeSessions = content.filter((u: any) => u.enabled).length;
      const linkedBrokers = content.filter((u: any) => u.brokerLinked).length;
      merged.userMetrics = [
        {
          label: "Total Users",
          value: str(totalUsers, "-"),
          delta: "From registered admin users",
          positive: totalUsers > 0,
        },
        {
          label: "Active Sessions",
          value: str(activeSessions, "-"),
          delta: "Enabled users in current page",
          positive: activeSessions > 0,
        },
        {
          label: "Total Transactions",
          value: "-",
          delta: "Backed by OMS summary below",
          positive: false,
        },
        {
          label: "System Health",
          value: str(linkedBrokers, "-"),
          delta: "Users with linked brokers",
          positive: linkedBrokers > 0,
        },
      ];
      merged.liveTraderActivity = content.slice(0, 6).map((u: any) => ({
        name: str(u.displayName || u.username, "Unknown trader"),
        tier: u.brokerLinked ? "Broker linked" : "Broker pending",
        online: Boolean(u.enabled),
      }));
    }
  }
  if (health.status === "fulfilled" && health.value.data?.data) {
    const d = health.value.data.data;
    merged.runtimeMetrics = [
      { label: "Kill Switch", value: String(Boolean(d.killSwitch) ? "ENABLED" : "DISABLED") },
      { label: "Live Trading Armed", value: String(Boolean(d.liveTradingArmed) ? "ARMED" : "DISARMED") },
      { label: "Uptime (sec)", value: str(d.uptimeSeconds, "-") },
    ];
  }
  if (ops.status === "fulfilled" && ops.value.data?.data) {
    const d = ops.value.data.data;
    merged.riskMetrics = [
      {
        label: "Registered Users",
        value: str(d.registeredUsers, "-"),
        level: "ok",
      },
      {
        label: "Running Strategies",
        value: str(d.runningStrategies, "-"),
        level: Number(d.runningStrategies ?? 0) > 0 ? "warn" : "ok",
      },
      {
        label: "WS Users (approx)",
        value: str(d.websocketUsersApprox, "-"),
        level: "ok",
      },
    ];
    const queues = d.rabbitQueues as Record<string, Record<string, unknown>> | undefined;
    if (queues != null) {
      merged.replayMetrics = Object.entries(queues).map(([queue, props]) => ({
        label: queue,
        value: str(props?.QUEUE_MESSAGE_COUNT ?? props?.messageCount, "-"),
      }));
    }
  }
  if (readiness.status === "fulfilled" && readiness.value.data?.data) {
    const d = readiness.value.data.data as Record<string, unknown>;
    merged.brokerHealth = [
      {
        name: "Platform readiness",
        status:
          String(d.status ?? d.overallStatus ?? "").toUpperCase() === "DOWN"
            ? "DOWN"
            : String(d.status ?? d.overallStatus ?? "").toUpperCase() === "WARNING"
              ? "WARNING"
              : "HEALTHY",
      },
    ];
  }
  if (oms.status === "fulfilled" && oms.value.data?.data) {
    const d = oms.value.data.data as Record<string, unknown>;
    merged.omsStats = Object.entries(d).slice(0, 6).map(([label, value]) => ({
      label,
      value: str(value, "-"),
    }));
  }
  if (audit.status === "fulfilled" && Array.isArray(audit.value.data?.data)) {
    merged.auditActivity = audit.value.data.data.slice(0, 6).map((a: any) => ({
      time: str(a.createdAt ?? a.time, "-"),
      action: str(a.action ?? a.resourceType, "Audit event"),
      actor: str(a.actorUserId ?? a.actor, "System"),
    }));
  }
  if (alerts.status === "fulfilled" && Array.isArray(alerts.value.data?.data)) {
    merged.alerts = alerts.value.data.data.slice(0, 4).map((a: any) => ({
      level: a.level === "critical" ? "critical" : a.level === "warn" ? "warn" : "info",
      title: str(a.title, "Alert"),
      detail: str(a.detail, ""),
    }));
  }

  return merged;
}
