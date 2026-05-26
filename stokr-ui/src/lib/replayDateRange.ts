export type DateRangePreset = "1W" | "15D" | "1M" | "3M" | "6M" | "1Y";

export type ReplayCoverageBounds = {
  coveredFrom: string | null;
  coveredTo: string | null;
  latestCandleAt: string | null;
  effectiveReplayEnd: string | null;
};

function parseInstant(value: string | null | undefined): Date | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Latest instant that can be used as replay range end (never beyond last bar). */
export function resolveReplayMaxEnd(bounds?: ReplayCoverageBounds | null, now = new Date()): Date {
  const effective = parseInstant(bounds?.effectiveReplayEnd);
  if (effective) return effective;
  const latest = parseInstant(bounds?.latestCandleAt);
  if (latest) {
    const end = new Date(latest);
    end.setMinutes(end.getMinutes() + 1);
    return end;
  }
  return now;
}

export function resolveReplayMinStart(bounds?: ReplayCoverageBounds | null): Date | null {
  return parseInstant(bounds?.coveredFrom);
}

export function computePresetRange(
  preset: DateRangePreset,
  bounds?: ReplayCoverageBounds | null,
  now = new Date(),
): { from: Date; to: Date } {
  const maxEnd = resolveReplayMaxEnd(bounds, now);
  const end = new Date(maxEnd.getTime() < now.getTime() ? maxEnd : now);
  end.setSeconds(0, 0);

  const start = new Date(end);
  switch (preset) {
    case "1W":
      start.setDate(start.getDate() - 7);
      break;
    case "15D":
      start.setDate(start.getDate() - 15);
      break;
    case "1M":
      start.setMonth(start.getMonth() - 1);
      break;
    case "3M":
      start.setMonth(start.getMonth() - 3);
      break;
    case "6M":
      start.setMonth(start.getMonth() - 6);
      break;
    case "1Y":
      start.setFullYear(start.getFullYear() - 1);
      break;
    default:
      start.setMonth(start.getMonth() - 3);
  }

  return clampReplayRange(start, end, bounds);
}

export function clampReplayRange(
  from: Date,
  to: Date,
  bounds?: ReplayCoverageBounds | null,
  now = new Date(),
): { from: Date; to: Date } {
  const minStart = resolveReplayMinStart(bounds);
  const maxEnd = resolveReplayMaxEnd(bounds, now);

  let clampedTo = to.getTime() > maxEnd.getTime() ? maxEnd : to;
  let clampedFrom = from;

  if (minStart && clampedFrom.getTime() < minStart.getTime()) {
    clampedFrom = minStart;
  }
  if (clampedTo.getTime() > now.getTime()) {
    clampedTo = new Date(now.getTime() > maxEnd.getTime() ? maxEnd : now);
    clampedTo.setSeconds(0, 0);
  }
  if (clampedFrom.getTime() >= clampedTo.getTime()) {
    clampedFrom = new Date(clampedTo.getTime() - 24 * 60 * 60 * 1000);
    if (minStart && clampedFrom.getTime() < minStart.getTime()) {
      clampedFrom = minStart;
    }
  }
  return { from: clampedFrom, to: clampedTo };
}

export function formatReplayBoundsLabel(bounds?: ReplayCoverageBounds | null): string | null {
  const from = parseInstant(bounds?.coveredFrom);
  const end = parseInstant(bounds?.effectiveReplayEnd ?? bounds?.latestCandleAt);
  if (!from || !end) return null;
  const opts: Intl.DateTimeFormatOptions = {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "Asia/Kolkata",
  };
  return `${from.toLocaleDateString("en-IN", opts)} → ${end.toLocaleDateString("en-IN", opts)} (IST)`;
}
