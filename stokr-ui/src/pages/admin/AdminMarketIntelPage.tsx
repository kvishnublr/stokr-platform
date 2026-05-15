import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  MarketFreshnessPanel,
  MarketIntelligenceGrid,
} from "../../components/admin/cockpit/AdminCockpitPanels";
import type { OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";

export function AdminMarketIntelPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: async () => {
      const res = await api.get("/api/admin/operations/snapshot");
      return res.data?.data as OpsSnapshot;
    },
    staleTime: 60_000,
  });
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Market intelligence</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Centralized candle store + broker session signals.{" "}
          <Link to="/admin/ops" className="font-mono text-primary underline-offset-2 hover:underline">
            Operations cockpit
          </Link>{" "}
          for live control actions.
        </p>
      </div>
      <MarketIntelligenceGrid snapshot={snapshot.data} />
      <MarketFreshnessPanel snapshot={snapshot.data} />
    </div>
  );
}
