import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, CheckCircle2, ChevronDown, Info, Search, XCircle } from "lucide-react";
import { GlassPanel } from "../../components/ds/GlassPanel";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

type LogLevel = "ALL" | "ERROR" | "WARN" | "INFO" | "DEBUG";

type LogEntry = {
  ts: string;
  level: string;
  logger: string;
  msg: string;
  ex?: string;
};

const LEVEL_ORDER: Record<string, number> = { ERROR: 0, WARN: 1, INFO: 2, DEBUG: 3, TRACE: 4 };

function passesFilter(entry: LogEntry, level: LogLevel, search: string): boolean {
  if (level !== "ALL") {
    const entryOrd = LEVEL_ORDER[entry.level] ?? 99;
    const filterOrd = LEVEL_ORDER[level] ?? 99;
    if (entryOrd > filterOrd) return false;
  }
  if (search) {
    const q = search.toLowerCase();
    return (
      entry.msg.toLowerCase().includes(q) ||
      entry.logger.toLowerCase().includes(q) ||
      (entry.ex?.toLowerCase().includes(q) ?? false)
    );
  }
  return true;
}

function levelColor(level: string, isLight: boolean): string {
  switch (level) {
    case "ERROR":
      return isLight ? "text-red-600" : "text-red-400";
    case "WARN":
      return isLight ? "text-amber-600" : "text-amber-400";
    case "DEBUG":
      return isLight ? "text-neutral-400" : "text-neutral-500";
    default:
      return isLight ? "text-emerald-700" : "text-emerald-400";
  }
}

function LevelIcon({ level }: { level: string }) {
  switch (level) {
    case "ERROR":
      return <XCircle className="h-3.5 w-3.5 shrink-0 text-red-400" />;
    case "WARN":
      return <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-400" />;
    case "DEBUG":
      return <Info className="h-3.5 w-3.5 shrink-0 text-neutral-500" />;
    default:
      return <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400" />;
  }
}

function formatTs(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString("en-IN", { hour12: false });
  } catch {
    return iso;
  }
}

const LEVELS: LogLevel[] = ["ALL", "ERROR", "WARN", "INFO", "DEBUG"];
const MAX_DISPLAYED = 1000;

export function AdminLogsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [level, setLevel] = useState<LogLevel>("ALL");
  const [search, setSearch] = useState("");
  const [autoScroll, setAutoScroll] = useState(true);
  const [connected, setConnected] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Load recent logs on mount
  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    fetch("/api/admin/logs/recent?n=200", { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => r.json())
      .then((body) => {
        const rows = (body?.data ?? []) as LogEntry[];
        setEntries(rows);
      })
      .catch(() => {});
  }, []);

  // SSE stream
  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    const abort = new AbortController();
    let active = true;

    (async () => {
      try {
        const res = await fetch("/api/admin/logs/stream", {
          headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
          signal: abort.signal,
        });
        if (!res.ok || !res.body) return;
        setConnected(true);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = "";
        while (active) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });
          let sep: number;
          while ((sep = buf.indexOf("\n\n")) >= 0) {
            const frame = buf.slice(0, sep).trim();
            buf = buf.slice(sep + 2);
            if (!frame) continue;
            let data = "";
            for (const line of frame.split("\n")) {
              if (line.startsWith("data:")) data = line.slice(5).trim();
            }
            if (!data) continue;
            try {
              const entry = JSON.parse(data) as LogEntry;
              setEntries((prev) => {
                const next = [...prev, entry];
                return next.length > MAX_DISPLAYED ? next.slice(next.length - MAX_DISPLAYED) : next;
              });
            } catch {
              /* ignore malformed */
            }
          }
        }
      } catch {
        /* aborted or network error */
      } finally {
        setConnected(false);
      }
    })();

    return () => {
      active = false;
      abort.abort();
    };
  }, []);

  // Auto-scroll
  useEffect(() => {
    if (autoScroll) {
      bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [entries, autoScroll]);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    if (!atBottom) setAutoScroll(false);
  }, []);

  const scrollToBottom = () => {
    setAutoScroll(true);
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  const filtered = entries.filter((e) => passesFilter(e, level, search));

  return (
    <div className="flex flex-col gap-4 p-4 md:p-6">
      <div className="flex flex-col gap-1">
        <h1 className={cn("text-xl font-bold", isLight ? "text-neutral-900" : "text-white")}>Live Logs</h1>
        <p className={cn("text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
          Real-time application log stream · last {MAX_DISPLAYED} entries retained
        </p>
      </div>

      {/* Toolbar */}
      <GlassPanel className="flex flex-wrap items-center gap-3 p-3">
        {/* Level filter */}
        <div className="flex gap-1">
          {LEVELS.map((l) => (
            <button
              key={l}
              type="button"
              onClick={() => setLevel(l)}
              className={cn(
                "rounded-lg px-2.5 py-1 text-xs font-semibold uppercase tracking-wide transition",
                level === l
                  ? isLight
                    ? "bg-blue-600 text-white"
                    : "bg-blue-500/80 text-white"
                  : isLight
                    ? "bg-neutral-100 text-neutral-600 hover:bg-neutral-200"
                    : "bg-white/5 text-neutral-400 hover:bg-white/10",
              )}
            >
              {l}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className={cn("flex flex-1 items-center gap-2 rounded-lg border px-3 py-1.5 text-sm",
          isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-white/5")}>
          <Search className="h-3.5 w-3.5 shrink-0 text-neutral-400" />
          <input
            type="text"
            placeholder="Filter by message or logger…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className={cn("w-full bg-transparent text-sm outline-none placeholder:text-neutral-400",
              isLight ? "text-neutral-900" : "text-neutral-100")}
          />
        </div>

        {/* Status dot */}
        <div className="flex items-center gap-1.5 text-xs">
          <span className={cn("h-2 w-2 rounded-full", connected ? "bg-emerald-400 animate-pulse" : "bg-neutral-500")} />
          <span className={isLight ? "text-neutral-500" : "text-neutral-400"}>
            {connected ? "live" : "offline"}
          </span>
        </div>

        <span className={cn("text-xs tabular-nums", isLight ? "text-neutral-400" : "text-neutral-500")}>
          {filtered.length} lines
        </span>

        <button
          type="button"
          onClick={() => setEntries([])}
          className={cn("rounded-lg px-2.5 py-1 text-xs font-medium transition",
            isLight ? "bg-neutral-100 text-neutral-600 hover:bg-neutral-200" : "bg-white/5 text-neutral-400 hover:bg-white/10")}
        >
          Clear
        </button>
      </GlassPanel>

      {/* Log pane */}
      <GlassPanel className="relative flex min-h-0 flex-col overflow-hidden p-0">
        <div
          ref={containerRef}
          onScroll={handleScroll}
          className="h-[calc(100vh-20rem)] overflow-y-auto font-mono text-xs"
        >
          {filtered.length === 0 ? (
            <div className={cn("flex h-32 items-center justify-center text-sm", isLight ? "text-neutral-400" : "text-neutral-500")}>
              No log entries match current filter
            </div>
          ) : (
            <table className="w-full border-collapse">
              <tbody>
                {filtered.map((entry, i) => (
                  <tr
                    key={i}
                    className={cn(
                      "border-b align-top",
                      isLight
                        ? "border-neutral-100 hover:bg-neutral-50"
                        : "border-white/[0.04] hover:bg-white/[0.03]",
                      entry.level === "ERROR" && (isLight ? "bg-red-50/60" : "bg-red-500/5"),
                      entry.level === "WARN" && (isLight ? "bg-amber-50/40" : "bg-amber-500/5"),
                    )}
                  >
                    <td className={cn("whitespace-nowrap pl-3 pr-2 pt-1.5 pb-1 tabular-nums",
                      isLight ? "text-neutral-400" : "text-neutral-500")}>
                      {formatTs(entry.ts)}
                    </td>
                    <td className="px-2 pt-1.5 pb-1">
                      <LevelIcon level={entry.level} />
                    </td>
                    <td className={cn("px-1 pt-1.5 pb-1 font-semibold uppercase tabular-nums w-10",
                      levelColor(entry.level, isLight))}>
                      {entry.level}
                    </td>
                    <td className={cn("px-2 pt-1.5 pb-1 whitespace-nowrap",
                      isLight ? "text-blue-700" : "text-blue-400")}>
                      {entry.logger}
                    </td>
                    <td className={cn("px-2 pt-1.5 pb-1 break-all",
                      isLight ? "text-neutral-800" : "text-neutral-200")}>
                      {entry.msg}
                      {entry.ex ? (
                        <span className={cn("ml-2 text-[11px]", isLight ? "text-red-600" : "text-red-400")}>
                          {entry.ex}
                        </span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Scroll-to-bottom fab */}
        {!autoScroll && (
          <button
            type="button"
            onClick={scrollToBottom}
            className={cn(
              "absolute bottom-4 right-4 flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold shadow-lg transition",
              isLight
                ? "bg-neutral-800 text-white hover:bg-neutral-700"
                : "bg-neutral-700 text-white hover:bg-neutral-600",
            )}
          >
            <ChevronDown className="h-3.5 w-3.5" />
            Jump to bottom
          </button>
        )}
      </GlassPanel>
    </div>
  );
}
