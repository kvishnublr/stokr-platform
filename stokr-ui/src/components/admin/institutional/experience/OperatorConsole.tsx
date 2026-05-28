import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Command, Keyboard, X, Zap } from "lucide-react";
import { cn } from "../../../../lib/utils";

const QUICK_ACTIONS = [
  { keys: "G then D", label: "Safety & diagnostics", to: "/admin/safety-diagnostics" },
  { keys: "G then S", label: "Signal war room", to: "/admin/signals" },
  { keys: "G then R", label: "Risk terminal", to: "/admin/risk-dashboard" },
  { keys: "G then O", label: "OMS monitor", to: "/admin/oms" },
  { keys: "G then C", label: "Command center", to: "/admin" },
  { keys: "G then L", label: "Research lab", to: "/admin/research-lab" },
  { keys: "?", label: "Toggle console", to: "" },
];

export function OperatorConsole({ isLight }: { isLight: boolean }) {
  const [open, setOpen] = useState(false);
  const [pendingG, setPendingG] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;

      if (e.key === "?" || (e.key === "/" && e.shiftKey)) {
        e.preventDefault();
        setOpen((v) => !v);
        return;
      }

      if (e.key === "Escape") {
        setOpen(false);
        setPendingG(false);
        return;
      }

      if (e.key.toLowerCase() === "g") {
        setPendingG(true);
        window.setTimeout(() => setPendingG(false), 1200);
        return;
      }

      if (pendingG) {
        const map: Record<string, string> = {
          d: "/admin/safety-diagnostics",
          s: "/admin/signals",
          r: "/admin/risk-dashboard",
          o: "/admin/oms",
          c: "/admin",
          l: "/admin/research-lab",
          t: "/admin/strategies",
        };
        const dest = map[e.key.toLowerCase()];
        if (dest) {
          e.preventDefault();
          navigate(dest);
          setOpen(false);
        }
        setPendingG(false);
      }
    };

    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navigate, pendingG]);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "fixed bottom-6 right-6 z-40 flex h-12 w-12 items-center justify-center rounded-full border shadow-lg transition hover:scale-105",
          isLight ? "border-neutral-300 bg-white text-neutral-800" : "border-neutral-700 bg-neutral-900 text-neutral-100",
        )}
        title="Operator console (?)"
      >
        <Command className="h-5 w-5" />
      </button>

      {open ? (
        <div className="fixed inset-0 z-50 flex items-end justify-end p-6 sm:items-center sm:justify-center">
          <div className="absolute inset-0 bg-black/40 backdrop-blur-[2px]" onClick={() => setOpen(false)} />
          <div
            className={cn(
              "relative w-full max-w-md rounded-2xl border p-5 shadow-2xl",
              isLight ? "border-neutral-200 bg-white" : "border-neutral-700 bg-neutral-950",
            )}
          >
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Zap className="h-4 w-4 text-blue-400" />
                <p className="text-sm font-semibold">Operator console</p>
              </div>
              <button type="button" onClick={() => setOpen(false)} className="rounded-lg p-1 opacity-60 hover:opacity-100">
                <X className="h-4 w-4" />
              </button>
            </div>
            <p className={cn("mb-3 text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
              Command-center keyboard workflows — press <kbd className="rounded border px-1">G</kbd> then a lane key.
            </p>
            <div className="space-y-2">
              {QUICK_ACTIONS.map((a) => (
                a.to ? (
                  <Link
                    key={a.label}
                    to={a.to}
                    onClick={() => setOpen(false)}
                    className={cn(
                      "flex items-center justify-between rounded-xl border px-3 py-2 text-sm transition hover:-translate-y-0.5",
                      isLight ? "border-neutral-200 hover:bg-neutral-50" : "border-neutral-800 hover:bg-neutral-900",
                    )}
                  >
                    <span>{a.label}</span>
                    <span className="flex items-center gap-1 font-mono text-[10px] opacity-60">
                      <Keyboard className="h-3 w-3" /> {a.keys}
                    </span>
                  </Link>
                ) : (
                  <div key={a.label} className={cn("flex items-center justify-between rounded-xl border px-3 py-2 text-sm", isLight ? "border-neutral-200" : "border-neutral-800")}>
                    <span>{a.label}</span>
                    <span className="font-mono text-[10px] opacity-60">{a.keys}</span>
                  </div>
                )
              ))}
            </div>
            {pendingG ? (
              <p className="mt-3 text-center text-[11px] text-blue-400 animate-pulse">Awaiting lane key…</p>
            ) : null}
          </div>
        </div>
      ) : null}
    </>
  );
}
