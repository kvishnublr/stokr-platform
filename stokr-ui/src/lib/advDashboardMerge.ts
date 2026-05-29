import type { AdvTerminalSnapshot } from "../api/advDashboard";

const IST = "Asia/Kolkata";

/** Client-side NSE regular session (09:15–15:30 IST, weekdays). */
export function isNseSessionOpenClient(now = new Date()): boolean {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: IST,
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(now);
  const weekday = parts.find((p) => p.type === "weekday")?.value ?? "";
  if (weekday === "Sat" || weekday === "Sun") {
    return false;
  }
  const hour = Number(parts.find((p) => p.type === "hour")?.value ?? 0);
  const minute = Number(parts.find((p) => p.type === "minute")?.value ?? 0);
  const mins = hour * 60 + minute;
  return mins >= 9 * 60 + 15 && mins <= 15 * 60 + 30;
}

export function advScanPollMs(marketOpen: boolean | undefined): number {
  return (marketOpen ?? isNseSessionOpenClient()) ? 5_000 : 15_000;
}

/** Keep last good scanner snapshot when a poll returns empty or drops session flags mid-day. */
export function mergeAdvTerminalSnapshot(
  prev: AdvTerminalSnapshot | undefined,
  next: AdvTerminalSnapshot,
): AdvTerminalSnapshot {
  if (!prev) {
    return normalizeTerminalSnapshot(next);
  }

  const clientOpen = isNseSessionOpenClient();
  const merged: AdvTerminalSnapshot = { ...next };

  if (!next.scannerRows?.length && prev.scannerRows?.length) {
    merged.scannerRows = prev.scannerRows;
  }
  if (!next.liveCards?.length && prev.liveCards?.length) {
    merged.liveCards = prev.liveCards;
  }

  const nextMetrics = { ...(next.metrics ?? {}) };
  const prevMetrics = prev.metrics ?? {};
  if ((Number(nextMetrics.stocksTracked) || 0) === 0 && (Number(prevMetrics.stocksTracked) || 0) > 0) {
    nextMetrics.stocksTracked = prevMetrics.stocksTracked;
  }
  if ((Number(nextMetrics.activeSetups) || 0) === 0 && merged.scannerRows?.length) {
    nextMetrics.activeSetups = merged.scannerRows.length;
  }
  if (
    (nextMetrics.marketBreadth === "0:0" || nextMetrics.marketBreadth === "—") &&
    prevMetrics.marketBreadth &&
    prevMetrics.marketBreadth !== "0:0"
  ) {
    nextMetrics.marketBreadth = prevMetrics.marketBreadth;
  }
  merged.metrics = nextMetrics;

  if (!next.marketOpen && prev.marketOpen && clientOpen) {
    merged.marketOpen = true;
  } else if (!next.marketOpen && clientOpen && (merged.scannerRows?.length ?? 0) > 0) {
    merged.marketOpen = true;
  }

  if (next.liveControl || prev.liveControl) {
    const lc = { ...(prev.liveControl ?? {}), ...(next.liveControl ?? {}) };
    if (!lc.marketOpen && (prev.liveControl?.marketOpen || clientOpen)) {
      lc.marketOpen = prev.liveControl?.marketOpen ?? clientOpen;
    }
    merged.liveControl = lc;
  }

  return normalizeTerminalSnapshot(merged);
}

function normalizeTerminalSnapshot(s: AdvTerminalSnapshot): AdvTerminalSnapshot {
  if (!s.scannerRows) {
    s.scannerRows = [];
  }
  if (!s.liveCards) {
    s.liveCards = [];
  }
  if (!s.metrics) {
    s.metrics = {};
  }
  return s;
}

export function mergeAdvMovers<T extends { symbol: string }>(
  prev: T[] | undefined,
  next: T[],
): T[] {
  if (next.length > 0) {
    return next;
  }
  if (prev?.length && isNseSessionOpenClient()) {
    return prev;
  }
  return next;
}
