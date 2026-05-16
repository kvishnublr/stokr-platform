import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { api, parseAxiosMessage } from "../api/client";

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
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-neutral-900">{meta.title}</h1>
        <p className="mt-1 text-sm text-neutral-600">{meta.description}</p>
      </div>

      {query.isError ? (
        <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load section data: {parseAxiosMessage(query.error)}
          <button type="button" className="ml-2 underline" onClick={() => void query.refetch()}>
            Retry
          </button>
        </div>
      ) : null}

      {query.isLoading ? (
        <div className="rounded-xl border border-neutral-200 bg-white p-4 text-sm text-neutral-500">Loading...</div>
      ) : null}

      {!query.isLoading && !query.isError && rows.length === 0 ? (
        <div className="rounded-xl border border-neutral-200 bg-white p-4 text-sm text-neutral-500">No data available.</div>
      ) : null}

      {rows.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-neutral-200 bg-white">
          <table className="w-full text-left text-sm">
            <tbody>
              {rows.map((row) => (
                <tr key={String(row.key)} className="border-b border-neutral-100 last:border-b-0">
                  <td className="w-56 px-4 py-2.5 font-medium text-neutral-700">{String(row.key)}</td>
                  <td className="px-4 py-2.5 text-neutral-900">{String(row.value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}
