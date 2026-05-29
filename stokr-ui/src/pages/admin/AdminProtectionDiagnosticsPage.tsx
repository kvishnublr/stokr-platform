import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

type ProtectionDiagnostics = {
  windowStart: string;
  windowEnd: string;
  totalProtectionExits: number;
  prematureVolumeVacuumExits: number;
  minHoldBypassedCount: number;
  avgHoldSeconds: number;
  exitsByCategory: Record<string, number>;
  recentExits: Array<{
    signalId: string;
    strategyName: string;
    symbol: string;
    holdSeconds: number;
    exitCategory: string;
    exitReason: string;
    minHoldBypassed: boolean;
    pressureTrigger: string;
  }>;
};

export function AdminProtectionDiagnosticsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const q = useQuery({
    queryKey: ["admin-protection-diagnostics"],
    queryFn: async () =>
      (await api.get("/api/admin/diagnostics/protection")).data?.data as ProtectionDiagnostics,
    refetchInterval: 30_000,
  });
  const d = q.data;

  return (
    <AdminPageShell title="Protection Diagnostics" subtitle="Persisted exit telemetry — volume vacuum and min-hold gates">
      <AdminSection title="Summary">
        <AdminPanel>
          {q.isLoading && <p className="text-sm text-neutral-500">Loading…</p>}
          {d && (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              {[
                { label: "Protection exits", value: d.totalProtectionExits },
                { label: "Premature volume vacuum", value: d.prematureVolumeVacuumExits, warn: d.prematureVolumeVacuumExits > 0 },
                { label: "Min-hold bypassed", value: d.minHoldBypassedCount, warn: d.minHoldBypassedCount > 0 },
                { label: "Avg hold (sec)", value: d.avgHoldSeconds.toFixed(1) },
              ].map((m) => (
                <div
                  key={m.label}
                  className={cn(
                    "rounded-lg border px-3 py-2",
                    m.warn ? "border-rose-500/40 bg-rose-500/10" : isLight ? "border-neutral-200" : "border-neutral-800",
                  )}
                >
                  <p className="text-[10px] uppercase text-neutral-500">{m.label}</p>
                  <p className="font-mono text-sm font-semibold">{m.value}</p>
                </div>
              ))}
            </div>
          )}
        </AdminPanel>
      </AdminSection>
      {d?.exitsByCategory && (
        <AdminSection title="By category">
          <AdminPanel>
            <pre className="overflow-auto text-xs">{JSON.stringify(d.exitsByCategory, null, 2)}</pre>
          </AdminPanel>
        </AdminSection>
      )}
      <AdminSection title="Recent exits">
        <AdminPanel>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-neutral-700 text-neutral-400">
                  <th className="p-2">Signal</th>
                  <th className="p-2">Symbol</th>
                  <th className="p-2">Hold s</th>
                  <th className="p-2">Category</th>
                  <th className="p-2">Bypass</th>
                  <th className="p-2">Reason</th>
                </tr>
              </thead>
              <tbody>
                {(d?.recentExits ?? []).map((r) => (
                  <tr key={r.signalId + r.exitReason} className="border-b border-neutral-800/60">
                    <td className="p-2 font-mono">{r.signalId?.slice(0, 8)}</td>
                    <td className="p-2">{r.symbol}</td>
                    <td className="p-2">{r.holdSeconds}</td>
                    <td className="p-2">{r.exitCategory}</td>
                    <td className="p-2">{r.minHoldBypassed ? "YES" : "no"}</td>
                    <td className="p-2 max-w-md truncate" title={r.exitReason}>
                      {r.exitReason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AdminPanel>
      </AdminSection>
    </AdminPageShell>
  );
}
