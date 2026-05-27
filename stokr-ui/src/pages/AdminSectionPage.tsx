import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { api, parseAxiosMessage } from "../api/client";
import { AdminPageShell, AdminPanel } from "../components/admin/institutional/AdminDesignSystem";
import { EmptyState } from "../components/ds/EmptyState";
import { PageSkeleton } from "../components/ds/SkeletonLoader";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";

type AdminSectionKind = "settings" | "security" | "reports" | "alerts";

const SECTION_META: Record<AdminSectionKind, { title: string; endpoint: string; description: string }> = {
  settings: {
    title: "Admin Settings",
    endpoint: "/api/admin/settings/summary",
    description: "Platform-level configuration and controls.",
  },
  security: {
    title: "Security Center",
    endpoint: "/api/admin/security/summary",
    description: "Auth posture, threats, and policy status.",
  },
  reports: {
    title: "Operational Reports",
    endpoint: "/api/admin/reports/summary",
    description: "Generated analytics and compliance reporting.",
  },
  alerts: {
    title: "Alert Center",
    endpoint: "/api/admin/alerts",
    description: "Active incidents and acknowledgement workflow.",
  },
};

export function AdminSectionPage({ section }: { section: AdminSectionKind }) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const meta = SECTION_META[section];
  const query = useQuery({
    queryKey: ["admin-section", section],
    queryFn: async () => {
      const res = await api.get(meta.endpoint);
      return res.data?.data;
    },
    staleTime: 10_000,
    refetchInterval: 30_000,
  });

  const rows = useMemo(() => {
    const data = query.data;
    if (Array.isArray(data)) return data;
    if (data != null && typeof data === "object") {
      return Object.entries(data as Record<string, unknown>).map(([key, value]) => ({
        key,
        value: value == null ? "-" : typeof value === "object" ? JSON.stringify(value) : String(value),
      }));
    }
    return [];
  }, [query.data]);

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Institutional console"
      title={meta.title}
      subtitle={meta.description}
    >
      {query.isError ? (
        <AdminPanel isLight={isLight} title="Load failed">
          <p className={cn("text-sm", isLight ? "text-rose-700" : "text-rose-300")}>
            Failed to load section data: {parseAxiosMessage(query.error)}
          </p>
          <button
            type="button"
            className={cn("mt-3 text-sm font-semibold underline", isLight ? "text-blue-700" : "text-blue-300")}
            onClick={() => void query.refetch()}
          >
            Retry
          </button>
        </AdminPanel>
      ) : null}

      {query.isLoading ? <PageSkeleton cards={3} /> : null}

      {!query.isLoading && !query.isError && rows.length === 0 ? (
        <EmptyState variant={isLight ? "light" : "dark"} title="No data available" description="This section has no rows to display yet." />
      ) : null}

      {rows.length > 0 ? (
        <AdminPanel isLight={isLight} title="Summary">
          <div className={cn("overflow-hidden rounded-xl border", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
            <table className="w-full text-left text-sm">
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={String(row.key)}
                    className={cn("border-b last:border-b-0", isLight ? "border-neutral-100" : "border-neutral-800/80")}
                  >
                    <td className={cn("w-56 px-4 py-2.5 font-medium", isLight ? "text-neutral-700" : "text-neutral-300")}>
                      {String(row.key)}
                    </td>
                    <td className={cn("px-4 py-2.5", isLight ? "text-neutral-900" : "text-neutral-100")}>{String(row.value)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AdminPanel>
      ) : null}
    </AdminPageShell>
  );
}
