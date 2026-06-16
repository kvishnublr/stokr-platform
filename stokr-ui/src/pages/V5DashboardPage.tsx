import { useSessionStore } from "../state/session";

export function V5DashboardPage() {
  const username = useSessionStore((s) => s.username);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <p className="text-sm text-muted-foreground">Welcome back, {username || "trader"}</p>
      </div>
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <div className="rounded-lg border bg-card p-4 text-card-foreground shadow-sm">
          <h3 className="text-sm font-medium">Account</h3>
          <p className="mt-2 text-2xl font-bold">Active</p>
        </div>
        <div className="rounded-lg border bg-card p-4 text-card-foreground shadow-sm">
          <h3 className="text-sm font-medium">Brokers</h3>
          <p className="mt-2 text-2xl font-bold">Connected</p>
        </div>
        <div className="rounded-lg border bg-card p-4 text-card-foreground shadow-sm">
          <h3 className="text-sm font-medium">Market Data</h3>
          <p className="mt-2 text-2xl font-bold">Streaming</p>
        </div>
      </div>
    </div>
  );
}
