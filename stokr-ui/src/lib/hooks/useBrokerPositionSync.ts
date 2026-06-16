import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { connectStomp } from "../realtime/stomp";

/** Query keys invalidated when broker position truth changes. */
export const BROKER_POSITION_QUERY_KEYS = [
  "positions-workstation",
  "trader-workstation",
  "trader-dashboard-workstation",
  "intraday-workstation",
  "intraday-broker-truth",
  "portfolio-exposure",
  "trader-dashboard-portfolio-overview",
] as const;

export function invalidateBrokerPositionQueries(queryClient: ReturnType<typeof useQueryClient>) {
  for (const key of BROKER_POSITION_QUERY_KEYS) {
    void queryClient.invalidateQueries({ queryKey: [key] });
  }
}

/**
 * Subscribe to broker position STOMP updates and invalidate position/P&L queries.
 * Falls back to HTTP polling via refetchInterval on the underlying useQuery hooks.
 */
export function useBrokerPositionSync(accessToken: string | null, userId: string | null, enabled = true) {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!enabled || !accessToken || !userId) return;

    const off = connectStomp(accessToken, userId, {
      onPosition: () => invalidateBrokerPositionQueries(queryClient),
      onPnl: () => invalidateBrokerPositionQueries(queryClient),
      onOrder: () => invalidateBrokerPositionQueries(queryClient),
    });

    return off;
  }, [accessToken, userId, enabled, queryClient]);
}

/** Poll interval: faster when broker is expected to be connected. */
export function brokerPositionPollMs(brokerConnected: boolean): number {
  return brokerConnected ? 2_000 : 8_000;
}

/** Pulse is live when last broker sync was within this window. */
export function isBrokerSyncPulseLive(lastSyncAt: string | null | undefined, windowMs = 4_000): boolean {
  if (!lastSyncAt) return false;
  const ms = Date.now() - new Date(lastSyncAt).getTime();
  return Number.isFinite(ms) && ms >= 0 && ms <= windowMs;
}

export function formatBrokerSyncAge(lastSyncAt: string | null | undefined): string | null {
  if (!lastSyncAt) return null;
  const ms = Date.now() - new Date(lastSyncAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return null;
  if (ms < 5_000) return "just now";
  if (ms < 60_000) return `${Math.round(ms / 1000)}s ago`;
  return `${Math.round(ms / 60_000)}m ago`;
}
