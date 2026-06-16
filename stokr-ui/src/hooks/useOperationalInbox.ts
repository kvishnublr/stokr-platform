import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { buildOperationalMessage, useMessagesStore, type OperationalMessage } from "../state/messages";
import { useNotificationStore, type FeedEvent } from "../state/notifications";
import { randomUuid } from "../lib/utils";

type ReadinessIssue = {
  severity?: string;
  code?: string;
  title?: string;
  detail?: string;
  action?: string;
  strategyKey?: string;
};

function mapSeverity(raw: unknown): FeedEvent["severity"] {
  const s = String(raw ?? "info").toUpperCase();
  if (s === "CRITICAL" || s === "ERROR") return "error";
  if (s === "WARNING" || s === "WARN") return "warning";
  if (s === "SUCCESS") return "success";
  return "info";
}

function mapMessageSeverity(raw: unknown): OperationalMessage["severity"] {
  return mapSeverity(raw);
}

function parseTs(raw: unknown): number {
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string" && raw.trim()) {
    const n = Date.parse(raw);
    if (Number.isFinite(n)) return n;
  }
  return Date.now();
}

function readinessActionPath(action: unknown, isAdmin: boolean): string | undefined {
  const a = String(action ?? "").trim();
  if (!a) return undefined;
  if (a === "connect_broker" || a === "RENEW_BROKER_SESSION") return isAdmin ? "/admin/broker-infrastructure" : "/brokers";
  if (a === "RECONNECT_FEED") return isAdmin ? "/admin/broker-infrastructure" : "/intraday";
  if (a === "run_checklist") return "/intraday";
  return undefined;
}

function mapReadinessIssues(
  issues: ReadinessIssue[] | undefined,
  isAdmin: boolean,
): OperationalMessage[] {
  if (!Array.isArray(issues)) return [];
  return issues.map((issue) =>
    buildOperationalMessage({
      id: `readiness:${issue.code ?? issue.title ?? randomUuid()}`,
      ts: Date.now(),
      severity: mapMessageSeverity(issue.severity),
      title: String(issue.title ?? issue.code ?? "Readiness issue"),
      detail: issue.detail ? String(issue.detail) : undefined,
      source: "Intraday readiness",
      actionPath: readinessActionPath(issue.action, isAdmin),
    }),
  );
}

function mapAdminAlerts(rows: unknown[]): OperationalMessage[] {
  return rows.map((row) => {
    const r = row as Record<string, unknown>;
    const code = String(r.code ?? r.title ?? randomUuid());
    return buildOperationalMessage({
      id: `admin-alert:${code}`,
      ts: parseTs(r.at ?? r.createdAt),
      severity: mapMessageSeverity(r.level),
      title: String(r.title ?? code),
      detail: r.detail != null ? String(r.detail) : undefined,
      source: String(r.subsystem ?? "Platform ops"),
      actionPath: "/admin/alerts",
    });
  });
}

function mapGuardTimeline(rows: unknown[]): FeedEvent[] {
  return rows.slice(0, 40).map((row) => {
    const r = row as Record<string, unknown>;
    const id = String(r.eventId ?? r.id ?? `${r.eventType ?? "event"}:${r.eventTime ?? r.emittedAt ?? randomUuid()}`);
    return {
      id: `guard:${id}`,
      ts: parseTs(r.eventTime ?? r.emittedAt ?? r.createdAt),
      severity: mapSeverity(r.severity ?? r.level),
      title: String(r.title ?? r.eventType ?? r.kind ?? "Execution event"),
      detail: r.detail != null ? String(r.detail) : r.symbol != null ? String(r.symbol) : undefined,
      topic: "orders",
    };
  });
}

export function useOperationalInbox({
  enabled,
  isAdmin,
  hasTraderAccess,
}: {
  enabled: boolean;
  isAdmin: boolean;
  hasTraderAccess: boolean;
}) {
  const hydrateMessages = useMessagesStore((s) => s.hydrate);
  const hydrateNotifications = useNotificationStore((s) => s.hydrate);

  const adminAlertsQuery = useQuery({
    queryKey: ["operational-inbox", "admin-alerts"],
    queryFn: async () => {
      const res = await api.get("/api/admin/alerts");
      return Array.isArray(res.data?.data) ? res.data.data : [];
    },
    enabled: enabled && isAdmin,
    staleTime: 20_000,
    refetchInterval: 30_000,
  });

  const readinessQuery = useQuery({
    queryKey: ["operational-inbox", "readiness"],
    queryFn: async () => {
      const res = await api.get("/api/trader/intraday/readiness");
      return res.data?.data as Record<string, unknown> | undefined;
    },
    enabled: enabled && hasTraderAccess,
    staleTime: 20_000,
    refetchInterval: 30_000,
  });

  const guardTimelineQuery = useQuery({
    queryKey: ["operational-inbox", "guard-timeline"],
    queryFn: async () => {
      const res = await api.get("/api/trader/terminal/execution-guard/timeline?limit=40");
      return Array.isArray(res.data?.data) ? res.data.data : [];
    },
    enabled: enabled && hasTraderAccess,
    staleTime: 20_000,
    refetchInterval: 30_000,
  });

  useEffect(() => {
    if (!enabled) return;
    const messages: OperationalMessage[] = [];

    if (isAdmin && adminAlertsQuery.isSuccess) {
      messages.push(...mapAdminAlerts(adminAlertsQuery.data ?? []));
    }

    if (hasTraderAccess && readinessQuery.isSuccess && readinessQuery.data) {
      const d = readinessQuery.data;
      messages.push(
        ...mapReadinessIssues(d.blockers as ReadinessIssue[] | undefined, isAdmin),
        ...mapReadinessIssues(d.warnings as ReadinessIssue[] | undefined, isAdmin),
        ...mapReadinessIssues(d.info as ReadinessIssue[] | undefined, isAdmin),
      );
    }

    if ((isAdmin && adminAlertsQuery.isSuccess) || (hasTraderAccess && readinessQuery.isSuccess)) {
      hydrateMessages(messages);
    }
  }, [
    enabled,
    adminAlertsQuery.isSuccess,
    adminAlertsQuery.data,
    readinessQuery.isSuccess,
    readinessQuery.data,
    hydrateMessages,
    isAdmin,
    hasTraderAccess,
  ]);

  useEffect(() => {
    if (!hasTraderAccess || !guardTimelineQuery.data?.length) return;
    hydrateNotifications(mapGuardTimeline(guardTimelineQuery.data));
  }, [guardTimelineQuery.data, hydrateNotifications, hasTraderAccess]);
}
