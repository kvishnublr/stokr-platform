import { cn } from "./utils";

/** Semantic status tones used across admin + trader surfaces. */
export type StatusTone = "critical" | "warn" | "info" | "success" | "neutral";

export type AccentTone = "indigo" | "blue" | "rose" | "emerald" | "amber" | "sky" | "violet" | "cyan";

/** Compact pill / insight chip — always high contrast in light and dark. */
export function toneChipClasses(isLight: boolean, tone: StatusTone): string {
  switch (tone) {
    case "critical":
      return isLight
        ? "border-rose-300 bg-rose-50 text-rose-900 hover:border-rose-400 hover:bg-rose-100"
        : "border-rose-500/40 bg-rose-500/10 text-rose-200 hover:bg-rose-500/15";
    case "warn":
      return isLight
        ? "border-amber-300 bg-amber-50 text-amber-950 hover:border-amber-400 hover:bg-amber-100"
        : "border-amber-500/40 bg-amber-500/10 text-amber-100 hover:bg-amber-500/15";
    case "info":
      return isLight
        ? "border-blue-300 bg-blue-50 text-blue-900 hover:border-blue-400 hover:bg-blue-100"
        : "border-blue-500/30 bg-blue-500/10 text-blue-100 hover:bg-blue-500/15";
    case "success":
      return isLight
        ? "border-emerald-300 bg-emerald-50 text-emerald-900 hover:border-emerald-400 hover:bg-emerald-100"
        : "border-emerald-500/40 bg-emerald-500/10 text-emerald-200 hover:bg-emerald-500/15";
    default:
      return isLight
        ? "border-neutral-300 bg-neutral-50 text-neutral-800 hover:bg-neutral-100"
        : "border-neutral-600 bg-neutral-800/70 text-neutral-200 hover:bg-neutral-800";
  }
}

/** Section container for grouped insights / alerts. */
export function toneSectionClasses(isLight: boolean, accent: AccentTone = "indigo"): string {
  const map: Record<AccentTone, string> = {
    indigo: isLight ? "border-indigo-200 bg-indigo-50/60" : "border-indigo-500/30 bg-indigo-500/10",
    blue: isLight ? "border-blue-200 bg-blue-50/60" : "border-blue-500/30 bg-blue-500/10",
    rose: isLight ? "border-rose-200 bg-rose-50/60" : "border-rose-500/30 bg-rose-500/10",
    emerald: isLight ? "border-emerald-200 bg-emerald-50/60" : "border-emerald-500/30 bg-emerald-500/10",
    amber: isLight ? "border-amber-200 bg-amber-50/60" : "border-amber-500/30 bg-amber-500/10",
    sky: isLight ? "border-sky-200 bg-sky-50/60" : "border-sky-500/30 bg-sky-500/10",
    violet: isLight ? "border-violet-200 bg-violet-50/60" : "border-violet-500/30 bg-violet-500/10",
    cyan: isLight ? "border-cyan-200 bg-cyan-50/60" : "border-cyan-500/30 bg-cyan-500/10",
  };
  return map[accent];
}

/** Uppercase section labels / eyebrows. */
export function toneEyebrowClasses(isLight: boolean, accent: AccentTone = "indigo"): string {
  const map: Record<AccentTone, string> = {
    indigo: isLight ? "text-indigo-800" : "text-indigo-400",
    blue: isLight ? "text-blue-800" : "text-blue-400",
    rose: isLight ? "text-rose-800" : "text-rose-400",
    emerald: isLight ? "text-emerald-800" : "text-emerald-400",
    amber: isLight ? "text-amber-900" : "text-amber-400",
    sky: isLight ? "text-sky-800" : "text-sky-400",
    violet: isLight ? "text-violet-800" : "text-violet-400",
    cyan: isLight ? "text-cyan-800" : "text-cyan-400",
  };
  return map[accent];
}

/** Alert banner with title + body text. */
export function toneBannerClasses(isLight: boolean, tone: StatusTone): string {
  return cn("rounded-xl border px-4 py-3", toneChipClasses(isLight, tone));
}

/** Primary / secondary action buttons on tinted surfaces. */
export function toneButtonClasses(
  isLight: boolean,
  variant: "primary" | "secondary" | "danger" | "ghost" = "secondary",
): string {
  switch (variant) {
    case "primary":
      return isLight
        ? "border-blue-600 bg-blue-600 text-white hover:bg-blue-700"
        : "border-blue-500 bg-blue-600 text-white hover:bg-blue-500";
    case "danger":
      return isLight
        ? "border-rose-700 bg-rose-700 text-white hover:bg-rose-800"
        : "border-rose-600 bg-rose-600 text-white hover:bg-rose-500";
    case "ghost":
      return isLight
        ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50"
        : "border-neutral-700 bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800";
    default:
      return isLight
        ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50"
        : "border-neutral-700 bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800";
  }
}

/** Map operational insight tone strings to StatusTone. */
export function mapInsightTone(tone: string | undefined): StatusTone {
  const t = String(tone ?? "").toLowerCase();
  if (t === "critical" || t === "bad" || t === "error") return "critical";
  if (t === "warn" || t === "warning") return "warn";
  if (t === "success" || t === "ok") return "success";
  if (t === "info") return "info";
  return "neutral";
}
