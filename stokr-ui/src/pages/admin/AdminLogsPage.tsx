import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertTriangle, CheckCircle2, ChevronDown, Info,
  Search, XCircle, Wifi, WifiOff, Trash2,
} from "lucide-react";

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

function LevelIcon({ level }: { level: string }) {
  switch (level) {
    case "ERROR": return <XCircle className="h-3.5 w-3.5 shrink-0 text-red-400" />;
    case "WARN":  return <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-400" />;
    case "DEBUG": return <Info className="h-3.5 w-3.5 shrink-0 text-slate-500" />;
    default:      return <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400" />;
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

const LEVEL_CFG: Record<string, { label: string; active: string; row: string; text: string }> = {
  ALL:   { label: "ALL",   active: "bg-blue-600 text-white shadow-sm",       row: "",                     text: "text-emerald-400" },
  ERROR: { label: "ERROR", active: "bg-red-600 text-white shadow-sm",         row: "bg-red-950/60",        text: "text-red-400 font-semibold" },
  WARN:  { label: "WARN",  active: "bg-amber-500 text-white shadow-sm",       row: "bg-amber-950/40",      text: "text-amber-400" },
  INFO:  { label: "INFO",  active: "bg-emerald-600 text-white shadow-sm",     row: "",                     text: "text-emerald-400" },
  DEBUG: { label: "DEBUG", active: "bg-slate-600 text-white shadow-sm",       row: "",                     text: "text-slate-500" },
};

function levelTextClass(level: string): string {
  return LEVEL_CFG[level]?.text ?? "text-emerald-400";
}

function rowBgClass(level: string): string {
  return LEVEL_CFG[level]?.row ?? "";
}

export function AdminLogsPage() {
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
            } catch { /* ignore */ }
          }
        }
      } catch { /* aborted */ } finally {
        setConnected(false);
      }
    })();

    return () => { active = false; abort.abort(); };
  }, []);

  // Auto-scroll
  useEffect(() => {
    if (autoScroll) bottomRef.current?.scrollIntoView({ behavior: "smooth" });
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
    <div className="flex h-full flex-col bg-slate-950 overflow-hidden">

      {/* ── Header bar ─────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-800 bg-slate-900/80 px-5 py-3.5 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-sm font-bold tracking-tight text-white">Live Logs</h1>
          <p className="text-[11px] text-slate-500 mt-0.5">
            Real-time application log stream · last {MAX_DISPLAYED} entries retained
          </p>
        </div>

        {/* Connection status */}
        <div className={`flex items-center gap-1.5 rounded-full border px-3 py-1 text-[11px] font-semibold ${
          connected
            ? "border-emerald-800 bg-emerald-950/60 text-emerald-400"
            : "border-slate-700 bg-slate-800/60 text-slate-500"}`}>
          {connected
            ? <><Wifi className="h-3 w-3" /><span className="animate-pulse">LIVE</span></>
            : <><WifiOff className="h-3 w-3" />OFFLINE</>}
        </div>
      </div>

      {/* ── Toolbar ──────────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-800 bg-slate-900/60 px-5 py-2.5 flex flex-wrap items-center gap-2.5">
        {/* Level filters */}
        <div className="flex gap-1">
          {LEVELS.map((l) => (
            <button key={l} type="button" onClick={() => setLevel(l)}
              className={`rounded-md px-2.5 py-1 text-[10px] font-bold uppercase tracking-widest transition-all ${
                level === l
                  ? LEVEL_CFG[l]?.active ?? "bg-blue-600 text-white"
                  : "bg-slate-800 text-slate-400 hover:bg-slate-700 hover:text-slate-300"
              }`}>
              {l}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="flex flex-1 items-center gap-2 rounded-lg border border-slate-700 bg-slate-800/80 px-3 py-1.5">
          <Search className="h-3.5 w-3.5 shrink-0 text-slate-500" />
          <input
            type="text"
            placeholder="Filter by message or logger…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-transparent text-xs text-slate-200 outline-none placeholder:text-slate-600 font-mono"
          />
          {search && (
            <button type="button" onClick={() => setSearch("")} className="text-slate-600 hover:text-slate-400">
              <XCircle className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        <span className="text-[11px] tabular-nums text-slate-600 font-mono">{filtered.length} lines</span>

        <button type="button" onClick={() => setEntries([])}
          className="flex items-center gap-1.5 rounded-md bg-slate-800 px-2.5 py-1 text-[10px] font-semibold text-slate-400 hover:bg-slate-700 hover:text-slate-200 transition-colors">
          <Trash2 className="h-3 w-3" /> Clear
        </button>
      </div>

      {/* ── Log pane ──────────────────────────────────────────────────────────── */}
      <div className="relative flex-1 min-h-0">
        <div
          ref={containerRef}
          onScroll={handleScroll}
          className="h-full overflow-y-auto font-mono text-[11.5px] leading-relaxed"
          style={{ background: "linear-gradient(180deg, #080c14 0%, #090d15 100%)" }}
        >
          {filtered.length === 0 ? (
            <div className="flex h-32 items-center justify-center text-sm text-slate-600">
              No log entries match current filter
            </div>
          ) : (
            <table className="w-full border-collapse">
              <tbody>
                {filtered.map((entry, i) => (
                  <tr key={i}
                    className={`group border-b border-slate-900/60 align-top transition-colors hover:bg-slate-800/30 ${rowBgClass(entry.level)}`}>
                    {/* Timestamp */}
                    <td className="whitespace-nowrap pl-4 pr-2 py-1 tabular-nums text-slate-600 w-20 select-none">
                      {formatTs(entry.ts)}
                    </td>

                    {/* Icon */}
                    <td className="px-1 py-1 w-5">
                      <LevelIcon level={entry.level} />
                    </td>

                    {/* Level badge */}
                    <td className={`px-1 py-1 w-12 font-bold uppercase tabular-nums text-[10px] ${levelTextClass(entry.level)}`}>
                      {entry.level}
                    </td>

                    {/* Logger */}
                    <td className="px-2 py-1 whitespace-nowrap text-sky-500/80 max-w-[200px] truncate group-hover:text-sky-400 transition-colors">
                      {entry.logger}
                    </td>

                    {/* Message */}
                    <td className="px-2 py-1 break-all text-slate-300">
                      {entry.msg}
                      {entry.ex && (
                        <span className="ml-2 text-[10px] text-red-400 opacity-80">{entry.ex}</span>
                      )}
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
          <button type="button" onClick={scrollToBottom}
            className="absolute bottom-4 right-4 flex items-center gap-1.5 rounded-full border border-blue-500/30 bg-blue-600/90 px-3.5 py-1.5 text-[11px] font-semibold text-white shadow-xl shadow-blue-900/40 backdrop-blur-sm hover:bg-blue-500 transition-colors">
            <ChevronDown className="h-3.5 w-3.5" /> Jump to bottom
          </button>
        )}
      </div>
    </div>
  );
}
