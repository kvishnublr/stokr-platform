import { toneChipClasses } from "../../../../lib/statusTone";

export type SignalProvenance = "LIVE" | "PAPER" | "SIMULATED" | "REPLAY" | "LAB" | "UNKNOWN";

export function resolveProvenance(pipeline: string | null | undefined): SignalProvenance {
  const v = String(pipeline ?? "").toUpperCase();
  if (v === "LIVE") return "LIVE";
  if (v === "PAPER") return "PAPER";
  if (v === "SIMULATED") return "PAPER";
  if (v.includes("REPLAY")) return "REPLAY";
  if (v.includes("LAB") || v.includes("TEST")) return "LAB";
  return "UNKNOWN";
}

export function provenanceShell(isLight: boolean, provenance: SignalProvenance) {
  if (provenance === "LIVE") {
    return isLight
      ? "border-rose-300/80 bg-gradient-to-br from-rose-50/90 via-white to-rose-50/40 shadow-[0_0_24px_-8px_rgba(244,63,94,0.35)]"
      : "border-rose-500/35 bg-gradient-to-br from-rose-950/40 via-neutral-950/80 to-rose-950/20 shadow-[0_0_32px_-12px_rgba(244,63,94,0.45)]";
  }
  if (provenance === "PAPER") {
    return isLight
      ? "border-amber-200/90 bg-gradient-to-br from-amber-50/80 via-white to-amber-50/30"
      : "border-amber-500/30 bg-gradient-to-br from-amber-950/30 via-neutral-950/80 to-amber-950/15";
  }
  if (provenance === "REPLAY") {
    return isLight
      ? "border-violet-300/70 bg-gradient-to-br from-violet-50/70 via-white to-indigo-50/40"
      : "border-violet-500/35 bg-gradient-to-br from-violet-950/35 via-neutral-950/80 to-indigo-950/20";
  }
  if (provenance === "LAB") {
    return isLight
      ? "border-cyan-300/70 bg-gradient-to-br from-cyan-50/70 via-white to-sky-50/40"
      : "border-cyan-500/35 bg-gradient-to-br from-cyan-950/35 via-neutral-950/80 to-sky-950/20";
  }
  return isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/60";
}

export function provenanceBadge(provenance: SignalProvenance, isLight = false) {
  switch (provenance) {
    case "LIVE":
      return toneChipClasses(isLight, "critical");
    case "PAPER":
      return toneChipClasses(isLight, "warn");
    case "REPLAY":
      return isLight
        ? "border-violet-300 bg-violet-50 text-violet-900"
        : "border-violet-500/40 bg-violet-500/15 text-violet-100";
    case "LAB":
      return isLight
        ? "border-cyan-300 bg-cyan-50 text-cyan-900"
        : "border-cyan-500/40 bg-cyan-500/15 text-cyan-100";
    default:
      return toneChipClasses(isLight, "neutral");
  }
}
