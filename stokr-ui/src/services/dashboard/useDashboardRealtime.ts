import { useEffect, useState } from "react";
import { connectStomp } from "../../lib/realtime/stomp";
import { useSessionStore } from "../../state/session";

export type RealtimePulse = {
  lastOrderEventAt: number | null;
  lastPnlEventAt: number | null;
  lastStrategyEventAt: number | null;
};

export function useDashboardRealtime(enabled = true): RealtimePulse {
  const accessToken = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const [pulse, setPulse] = useState<RealtimePulse>({
    lastOrderEventAt: null,
    lastPnlEventAt: null,
    lastStrategyEventAt: null,
  });

  useEffect(() => {
    if (!enabled || !accessToken || !userId) return;
    const off = connectStomp(accessToken, userId, {
      onOrder: () => setPulse((p) => ({ ...p, lastOrderEventAt: Date.now() })),
      onPnl: () => setPulse((p) => ({ ...p, lastPnlEventAt: Date.now() })),
      onStrategy: () => setPulse((p) => ({ ...p, lastStrategyEventAt: Date.now() })),
    });
    return () => off();
  }, [enabled, accessToken, userId]);

  return pulse;
}
