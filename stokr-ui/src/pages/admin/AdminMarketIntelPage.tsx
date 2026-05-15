import { useQuery } from "@tanstack/react-query";
import {
  MarketFreshnessPanel,
  MarketIntelligenceGrid,
} from "../../components/admin/cockpit/AdminCockpitPanels";
import { BrokerConnectionControlCenter } from "../../components/admin/BrokerConnectionControlCenter";
import { SystemReadinessBanner } from "../../components/admin/SystemReadinessBanner";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";

export function AdminMarketIntelPage() {
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  return (
    <div className="space-y-4 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Market intelligence</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Centralized candle store + broker OAuth plane. Orchestration actions live in the broker control center below.
        </p>
      </div>
      <SystemReadinessBanner snapshot={snapshot.data} />
      <BrokerConnectionControlCenter snapshot={snapshot.data} dense />
      <MarketIntelligenceGrid snapshot={snapshot.data} />
      <MarketFreshnessPanel snapshot={snapshot.data} />
    </div>
  );
}
