import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";

export function AdminInfrastructureHealthCenterPage() {
  const snapshot = useQuery({
    queryKey: ["admin-infra-health-center"],
    queryFn: async () => (await api.get("/api/admin/operations/snapshot")).data?.data,
    refetchInterval: 10_000,
  });

  const health = snapshot.data ?? {};
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Infrastructure Health Center</h1>
      <p className="text-xs text-muted-foreground">
        Broker websocket, queue lag, redis/postgres, and market freshness for validation runs.
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <HealthCard title="System" payload={health.system} />
        <HealthCard title="Broker Sessions" payload={health.brokerSessions} />
        <HealthCard title="Market Freshness" payload={health.marketFreshness} />
        <HealthCard title="OMS" payload={health.oms} />
      </div>
    </div>
  );
}

function HealthCard({ title, payload }: { title: string; payload: unknown }) {
  return (
    <div className="rounded-xl border border-border bg-card p-3">
      <div className="mb-2 text-sm font-semibold">{title}</div>
      <pre className="max-h-80 overflow-auto whitespace-pre-wrap text-[11px] text-muted-foreground">
        {JSON.stringify(payload ?? {}, null, 2)}
      </pre>
    </div>
  );
}
