import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { Activity, ArrowRight, Shield, Sparkles } from "lucide-react";
import { cn } from "../../../lib/utils";

const TO = "/admin/safety-diagnostics";

type Variant = "pill" | "hero" | "sidebar";

export function SafetyDiagnosticsLaunchLink({
  variant,
  isLight,
  killActive,
  className,
}: {
  variant: Variant;
  isLight: boolean;
  killActive?: boolean;
  className?: string;
}) {
  if (variant === "pill") {
    return (
      <Link
        to={TO}
        className={cn(
          "group relative inline-flex items-center gap-2 overflow-hidden rounded-full px-3 py-1.5 text-xs font-bold uppercase tracking-[0.12em] transition hover:scale-[1.02]",
          killActive
            ? "text-white shadow-[0_0_24px_rgba(244,63,94,0.45)]"
            : isLight
              ? "text-indigo-950 shadow-[0_4px_20px_rgba(99,102,241,0.25)]"
              : "text-white shadow-[0_0_28px_rgba(56,189,248,0.35)]",
          className,
        )}
      >
        <span
          className={cn(
            "absolute inset-0 rounded-full",
            killActive
              ? "bg-gradient-to-r from-rose-600 via-rose-500 to-amber-500"
              : "bg-gradient-to-r from-cyan-400 via-blue-500 to-violet-600",
          )}
        />
        <span className="absolute inset-0 rounded-full opacity-0 transition group-hover:opacity-100 bg-[linear-gradient(110deg,transparent_25%,rgba(255,255,255,0.35)_50%,transparent_75%)] bg-[length:200%_100%] animate-[shimmer_1.8s_ease-in-out_infinite]" />
        <Shield className="relative h-3.5 w-3.5 shrink-0 drop-shadow" />
        <span className="relative hidden sm:inline">Safety</span>
        <Sparkles className="relative h-3 w-3 shrink-0 opacity-80" />
      </Link>
    );
  }

  if (variant === "sidebar") {
    return (
      <Link
        to={TO}
        title="Safety & Diagnostics"
        className={cn(
          "group relative mx-2 mb-3 block overflow-hidden rounded-xl border p-3 transition hover:-translate-y-0.5",
          killActive
            ? isLight
              ? "border-rose-300 bg-gradient-to-br from-rose-50 via-white to-amber-50 shadow-[0_8px_30px_rgba(244,63,94,0.18)]"
              : "border-rose-500/40 bg-gradient-to-br from-rose-950/80 via-neutral-950 to-amber-950/40 shadow-[0_0_32px_rgba(244,63,94,0.2)]"
            : isLight
              ? "border-cyan-200/80 bg-gradient-to-br from-cyan-50 via-white to-violet-50 shadow-[0_8px_28px_rgba(59,130,246,0.15)]"
              : "border-cyan-500/30 bg-gradient-to-br from-cyan-950/50 via-neutral-950 to-violet-950/50 shadow-[0_0_36px_rgba(56,189,248,0.15)]",
          className,
        )}
      >
        <div
          className={cn(
            "pointer-events-none absolute -right-6 -top-6 h-16 w-16 rounded-full blur-2xl",
            killActive ? "bg-rose-500/30" : "bg-cyan-400/25",
          )}
        />
        <div className="relative flex items-start gap-2.5">
          <div
            className={cn(
              "flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border",
              killActive
                ? "border-rose-400/50 bg-rose-500/15 text-rose-400"
                : isLight
                  ? "border-cyan-300 bg-cyan-100 text-cyan-700"
                  : "border-cyan-500/40 bg-cyan-500/10 text-cyan-300",
            )}
          >
            <Shield className="h-4 w-4" />
          </div>
          <div className="min-w-0 flex-1">
            <p className={cn("text-[11px] font-bold leading-tight", isLight ? "text-neutral-900" : "text-white")}>
              Safety & Diagnostics
            </p>
            <p className={cn("mt-0.5 text-[10px] leading-snug", isLight ? "text-neutral-500" : "text-neutral-400")}>
              P2 ops · P3 OMS · kill switch
            </p>
            <div className="mt-2 flex flex-wrap gap-1">
              <Badge tone={killActive ? "bad" : "ok"} isLight={isLight}>
                {killActive ? "Kill ON" : "Live rail"}
              </Badge>
              <Badge tone="info" isLight={isLight}>P2</Badge>
              <Badge tone="info" isLight={isLight}>P3</Badge>
            </div>
          </div>
          <ArrowRight className={cn("relative h-4 w-4 shrink-0 transition group-hover:translate-x-0.5", isLight ? "text-neutral-400" : "text-neutral-500")} />
        </div>
      </Link>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className={cn("relative", className)}
    >
      <Link
        to={TO}
        className={cn(
          "group relative block overflow-hidden rounded-2xl border p-5 transition hover:-translate-y-1 hover:shadow-2xl",
          killActive
            ? isLight
              ? "border-rose-300/80 bg-gradient-to-br from-rose-50 via-white to-orange-50 shadow-[0_12px_40px_rgba(244,63,94,0.2)]"
              : "border-rose-500/40 bg-gradient-to-br from-rose-950/60 via-neutral-950 to-amber-950/30 shadow-[0_0_48px_rgba(244,63,94,0.25)]"
            : isLight
              ? "border-indigo-200/80 bg-gradient-to-br from-sky-50 via-white to-violet-100 shadow-[0_12px_40px_rgba(99,102,241,0.18)]"
              : "border-cyan-500/30 bg-gradient-to-br from-cyan-950/40 via-neutral-950 to-violet-950/40 shadow-[0_0_56px_rgba(56,189,248,0.18)]",
        )}
      >
        <div className="pointer-events-none absolute inset-0 opacity-40">
          <div
            className={cn(
              "absolute -left-1/4 top-0 h-full w-1/2 rotate-12 blur-3xl",
              killActive ? "bg-rose-500/40" : "bg-gradient-to-r from-cyan-400/30 to-violet-500/30",
            )}
          />
        </div>
        <div className="relative flex flex-wrap items-center justify-between gap-4">
          <div className="flex min-w-0 items-start gap-4">
            <div
              className={cn(
                "relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl border-2",
                killActive
                  ? "border-rose-400/60 bg-rose-500/20 text-rose-300 shadow-[0_0_24px_rgba(244,63,94,0.4)]"
                  : isLight
                    ? "border-cyan-300 bg-gradient-to-br from-cyan-100 to-violet-100 text-indigo-700 shadow-[0_8px_24px_rgba(59,130,246,0.25)]"
                    : "border-cyan-400/50 bg-gradient-to-br from-cyan-500/20 to-violet-500/20 text-cyan-200 shadow-[0_0_32px_rgba(56,189,248,0.35)]",
              )}
            >
              <Shield className="h-7 w-7" />
              {!killActive ? (
                <span className="absolute -right-1 -top-1 flex h-3 w-3">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-cyan-400 opacity-60" />
                  <span className="relative inline-flex h-3 w-3 rounded-full bg-cyan-400" />
                </span>
              ) : null}
            </div>
            <div className="min-w-0">
              <p
                className={cn(
                  "text-[10px] font-bold uppercase tracking-[0.2em]",
                  killActive ? "text-rose-500" : isLight ? "text-indigo-600" : "text-cyan-400",
                )}
              >
                Operational safety rail
              </p>
              <h2 className={cn("mt-1 text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
                Safety & Diagnostics
              </h2>
              <p className={cn("mt-1 max-w-xl text-sm leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>
                Feed health, safe startup, OMS kill switch, broker protection, exposure limits — one dedicated war room.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Badge tone={killActive ? "bad" : "ok"} isLight={isLight}>
                  {killActive ? "Kill switch armed" : "Kill switch off"}
                </Badge>
                <Badge tone="info" isLight={isLight}>P2 operational</Badge>
                <Badge tone="info" isLight={isLight}>P3 OMS safety</Badge>
                <Badge tone="neutral" isLight={isLight}>
                  <Activity className="mr-1 inline h-3 w-3" />
                  Auto-refresh
                </Badge>
              </div>
            </div>
          </div>
          <div
            className={cn(
              "inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold transition group-hover:gap-3",
              killActive
                ? "bg-rose-600 text-white shadow-lg shadow-rose-500/30"
                : isLight
                  ? "bg-gradient-to-r from-cyan-600 to-violet-600 text-white shadow-lg shadow-indigo-500/25"
                  : "bg-gradient-to-r from-cyan-500 to-violet-500 text-white shadow-lg shadow-cyan-500/25",
            )}
          >
            Open console
            <ArrowRight className="h-4 w-4 transition group-hover:translate-x-0.5" />
          </div>
        </div>
      </Link>
    </motion.div>
  );
}

function Badge({
  children,
  tone,
  isLight,
}: {
  children: ReactNode;
  tone: "ok" | "bad" | "info" | "neutral";
  isLight: boolean;
}) {
  const cls =
    tone === "bad"
      ? isLight
        ? "border-rose-300 bg-rose-100 text-rose-800"
        : "border-rose-500/40 bg-rose-500/15 text-rose-200"
      : tone === "ok"
        ? isLight
          ? "border-emerald-300 bg-emerald-100 text-emerald-800"
          : "border-emerald-500/40 bg-emerald-500/15 text-emerald-200"
        : tone === "info"
          ? isLight
            ? "border-sky-300 bg-sky-100 text-sky-800"
            : "border-cyan-500/30 bg-cyan-500/10 text-cyan-200"
          : isLight
            ? "border-neutral-300 bg-neutral-100 text-neutral-700"
            : "border-neutral-700 bg-neutral-900/80 text-neutral-300";

  return (
    <span className={cn("inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide", cls)}>
      {children}
    </span>
  );
}
