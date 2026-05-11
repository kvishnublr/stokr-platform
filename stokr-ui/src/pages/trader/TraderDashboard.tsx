import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { TraderDashboardLayout } from "../../components/trader/TraderDashboardLayout";
import { TraderMain, TraderRightRail, TraderSidebar, TraderTopbar } from "../../components/trader/TraderDashboardBlocks";
import { fetchTraderDashboardData } from "../../services/dashboard/traderDashboardService";
import { useDashboardRealtime } from "../../services/dashboard/useDashboardRealtime";
import { useSessionStore } from "../../state/session";

export function TraderDashboard() {
  const displayName = useSessionStore((s) => s.displayName) ?? useSessionStore((s) => s.username) ?? "Trader";
  const realtime = useDashboardRealtime(true);
  const query = useQuery({
    queryKey: ["trader-dashboard-v2"],
    queryFn: fetchTraderDashboardData,
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  const data = query.data;
  const realtimeConnected = useMemo(() => {
    const t = realtime.lastOrderEventAt ?? realtime.lastPnlEventAt ?? realtime.lastStrategyEventAt;
    return Boolean(t && Date.now() - t < 45_000);
  }, [realtime]);

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
    return <div className="flex h-full w-full items-center justify-center bg-gray-50 text-sm text-gray-600">Loading trader dashboard…</div>;
  }

  return (
    <TraderDashboardLayout
      sidebar={<TraderSidebar portfolio={data.portfolio} />}
      topbar={
        <TraderTopbar
          name={displayName}
          realtimeConnected={realtimeConnected}
          unread={data.notifications.unread}
          marketWatch={data.marketWatch}
        />
      }
      main={<TraderMain data={data} />}
      rightRail={<TraderRightRail data={data} />}
    />
  );
}
