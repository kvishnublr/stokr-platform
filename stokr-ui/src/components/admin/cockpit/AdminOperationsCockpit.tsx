import type { OpsSnapshot } from "./opsTypes";
import {
  BackfillOperationsPanel,
  BrokerInfrastructureGrid,
  ExecutionTimelinePanel,
  IncidentFeed,
  LiveSignalFeed,
  MarketFreshnessPanel,
  MarketIntelligenceGrid,
  OMSLatencyMonitor,
  OperationalHistoryStrip,
  ProjectionHealthPanel,
  QueueDepthMonitor,
  ReplayOpsGrid,
  SignalDistributionPanel,
  SignalRoutingMonitor,
  StrategyScannerGrid,
  TraderExecutionHealthGrid,
} from "./AdminCockpitPanels";

export function AdminOperationsCockpit({
  snapshot,
  isFetching,
}: {
  snapshot: OpsSnapshot | undefined;
  isFetching: boolean;
}) {
  return (
    <div className="space-y-2 text-[12px] leading-snug">
      {isFetching ? (
        <div className="text-[10px] font-mono text-muted-foreground" aria-live="polite">
          Refreshing snapshot...
        </div>
      ) : null}

      <div className="grid gap-3 xl:grid-cols-12">
        <div className="xl:col-span-12">
          <MarketFreshnessPanel snapshot={snapshot} />
        </div>
        <div className="xl:col-span-12">
          <MarketIntelligenceGrid snapshot={snapshot} />
        </div>
        <div className="xl:col-span-7">
          <BrokerInfrastructureGrid snapshot={snapshot} />
        </div>
        <div className="xl:col-span-5">
          <QueueDepthMonitor snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <StrategyScannerGrid snapshot={snapshot} />
        </div>
        <div className="xl:col-span-12">
          <SignalRoutingMonitor snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <SignalDistributionPanel snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <OMSLatencyMonitor snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <ReplayOpsGrid snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <ExecutionTimelinePanel />
        </div>
        <div className="xl:col-span-12">
          <OperationalHistoryStrip snapshot={snapshot} />
        </div>
        <div className="xl:col-span-12">
          <IncidentFeed snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <TraderExecutionHealthGrid snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <LiveSignalFeed snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <BackfillOperationsPanel snapshot={snapshot} />
        </div>
        <div className="xl:col-span-6">
          <ProjectionHealthPanel snapshot={snapshot} />
        </div>
      </div>
    </div>
  );
}
