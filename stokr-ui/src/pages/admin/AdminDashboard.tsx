import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { AdminMain, AdminRightRail, AdminSidebar, AdminTopbar } from "../../components/admin/AdminDashboardBlocks";
import type { AdminDashboardData } from "../../services/dashboard/types";
import { api } from "../../api/client";
import { fetchAdminDashboardData } from "../../services/dashboard/adminDashboardService";
import { useSessionStore } from "../../state/session";
import { performLogout } from "../../services/auth/logout";

export function AdminDashboard() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const accessToken = useSessionStore((s) => s.accessToken);
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  const isTrader = useSessionStore((s) => s.hasTraderAccess());
  const refreshToken = useSessionStore((s) => s.refreshToken);
  const query = useQuery({
    queryKey: ["admin-dashboard-v2"],
    queryFn: fetchAdminDashboardData,
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  const filteredData = useMemo<AdminDashboardData | null>(() => {
    if (!query.data) return null;
    const term = search.trim().toLowerCase();
    if (!term) return query.data;
    return {
      ...query.data,
      liveTraderActivity: query.data.liveTraderActivity.filter(
        (row) => row.name.toLowerCase().includes(term) || row.tier.toLowerCase().includes(term),
      ),
      alerts: query.data.alerts.filter(
        (row) => row.title.toLowerCase().includes(term) || row.detail.toLowerCase().includes(term),
      ),
      auditActivity: query.data.auditActivity.filter(
        (row) =>
          row.action.toLowerCase().includes(term) ||
          row.actor.toLowerCase().includes(term) ||
          row.time.toLowerCase().includes(term),
      ),
    };
  }, [query.data, search]);

  if (!accessToken) {
    return <Navigate to="/login" replace />;
  }
  if (!isAdmin || isTrader) {
    return <Navigate to="/dashboard" replace />;
  }

  if (query.isError) {
    return (
      <div className="flex h-full w-full items-center justify-center bg-gray-50">
        <div className="rounded-lg border border-red-200 bg-white p-4 text-sm text-red-700">
          Failed to load admin dashboard. <button className="ml-2 underline" onClick={() => query.refetch()}>Retry</button>
        </div>
      </div>
    );
  }

  if (!filteredData) {
    return <div className="flex h-full w-full items-center justify-center bg-gray-50 text-sm text-gray-600">Loading admin dashboard...</div>;
  }

  async function handleLogout() {
    await performLogout({
      remoteLogout: () => api.post("/api/auth/logout", { refreshToken }),
    });
    navigate("/login", { replace: true });
  }

  return (
    <div className="h-full w-full overflow-hidden bg-gray-50">
      <div className="flex h-full">
        <AdminSidebar />
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          <AdminTopbar
            onRefresh={() => void query.refetch()}
            search={search}
            onSearchChange={setSearch}
            onLogout={() => void handleLogout()}
          />
          <div className="flex min-h-0 min-w-0 flex-1 gap-5 overflow-hidden px-6 py-8">
            <main className="min-h-0 min-w-0 flex-1 overflow-y-auto pr-1">
              <AdminMain data={filteredData} />
            </main>
            <aside className="hidden w-96 flex-shrink-0 overflow-y-auto xl:block">
              <AdminRightRail data={filteredData} />
            </aside>
          </div>
        </div>
      </div>
    </div>
  );
}
