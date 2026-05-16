import { useQuery } from "@tanstack/react-query";
import { Navigate } from "react-router-dom";
import { TraderDashboardLayout } from "../../components/trader/TraderDashboardLayout";
import { TraderMain, TraderRightRail } from "../../components/trader/TraderDashboardBlocks";
import { fetchTraderDashboardData } from "../../services/dashboard/traderDashboardService";
import { useSessionStore } from "../../state/session";

export function TraderDashboard() {
  const accessToken = useSessionStore((s) => s.accessToken);
  const hasTraderAccess = useSessionStore((s) => s.hasTraderAccess());
  if (!accessToken) {
    return <Navigate to="/login" replace />;
  }
  if (!hasTraderAccess) {
    return <Navigate to="/login" replace />;
  }
  const query = useQuery({
    queryKey: ["trader-dashboard-v2"],
    queryFn: fetchTraderDashboardData,
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  const data = query.data;

  if (query.isError) {
    return (
      <div className="flex h-full w-full items-center justify-center bg-gray-50">
        <div className="rounded-lg border border-red-200 bg-white p-4 text-sm text-red-700">
          Failed to load dashboard. <button className="ml-2 underline" onClick={() => query.refetch()}>Retry</button>
        </div>
      </div>
    );
  }

  if (!data) {
    return <div className="flex h-full w-full items-center justify-center bg-gray-50 text-sm text-gray-600">Loading trader dashboard...</div>;
  }

  return (
    <TraderDashboardLayout main={<TraderMain data={data} />} rightRail={<TraderRightRail data={data} />} />
  );
}
