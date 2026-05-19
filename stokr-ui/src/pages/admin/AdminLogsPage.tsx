import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertTriangle, CheckCircle2, ChevronDown, ChevronRight,
  Info, Search, XCircle, Wifi, WifiOff, Trash2,
  Bug, Activity, Clock, Filter, X,
} from "lucide-react";

// ─── Types ───────────────────────────────────────────────────────────────────

type LogLevel = "ALL" | "ERROR" | "WARN" | "INFO" | "DEBUG";

type LogEntry = {
  ts: string;
  level: string;
  logger: string;
  msg: string;
  ex?: string;
};

// ─── Constants ────────────────────────────────────────────────────────────────

const LEVEL_ORDER: Record<string, number> = { ERROR: 0, WARN: 1, INFO: 2, DEBUG: 3, TRACE: 4 };
const MAX_DISPLAYED = 1000;
const LEVELS: LogLevel[] = ["ALL", "ERROR", "WARN", "INFO", "DEBUG"];

const LEVEL_STYLE: Record<string, {
  badge: string; row: string; text: string; activeBtnCls: string; icon: React.ElementType;
}> = {
  ERROR: {
    badge:        "bg-red-100 text-red-700 border border-red-200",
    row:          "bg-red-50/70 border-l-2 border-l-red-400",
    text:         "text-red-700",
    activeBtnCls: "bg-red-600 text-white shadow ring-1 ring-red-400",
    icon:         XCircle,
  },
  WARN: {
    badge:        "bg-amber-100 text-amber-700 border border-amber-200",
    row:          "bg-amber-50/60 border-l-2 border-l-amber-400",
    text:         "text-amber-700",
    activeBtnCls: "bg-amber-500 text-white shadow ring-1 ring-amber-300",
    icon:         AlertTriangle,
  },
  INFO: {
    badge:        "bg-sky-100 text-sky-700 border border-sky-200",
    row:          "",
    text:         "text-sky-700",
    activeBtnCls: "bg-sky-600 text-white shadow ring-1 ring-sky-400",
    icon:         CheckCircle2,
  },
  DEBUG: {
    badge:        "bg-slate-100 text-slate-500 border border-slate-200",
    row:          "",
    text:         "text-slate-500",
    activeBtnCls: "bg-slate-600 text-white shadow ring-1 ring-slate-400",
    icon:         Bug,
  },
  ALL: {
    badge:        "bg-blue-100 text-blue-700 border border-blue-200",
    row:          "",
    text:         "text-blue-700",
    activeBtnCls: "bg-blue-600 text-white shadow ring-1 ring-blue-400",
    icon:         Activity,
  },
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTs(iso: string, full = false): string {
  try {
    const d = new Date(iso);
    if (full) {
      return d.toLocaleString("en-IN", {
        day: "2-digit", month: "short", year: "numeric",
        hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
      });
    }
    return d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  } catch { return iso; }
}

function shortLogger(logger: string): string {
  const parts = logger.split(".");
  if (parts.length <= 2) return logger;
  return "…." + parts.slice(-2).join(".");
}

function highlight(text: string, q: string): React.ReactNode {
  if (!q) return text;
  const idx = text.toLowerCase().indexOf(q.toLowerCase());
  if (idx < 0) return text;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="bg-yellow-200 text-yellow-900 rounded px-0.5">{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  );
}

function passesFilter(
  entry: LogEntry, level: LogLevel, search: string,
  fromTs: string, toTs: string,
): boolean {
  if (level !== "ALL") {
    const entryOrd  = LEVEL_ORDER[entry.level] ?? 99;
    const filterOrd = LEVEL_ORDER[level] ?? 99;
    if (entryOrd > filterOrd) return false;
  }
  if (fromTs) {
    try { if (new Date(entry.ts) < new Date(fromTs)) return false; } catch { /**/ }
  }
  if (toTs) {
    try { if (new Date(entry.ts) > new Date(toTs)) return false; } catch { /**/ }
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

// ─── Sub-components ───────────────────────────────────────────────────────────

function LevelBadge({ level }: { level: string }) {
  const cfg = LEVEL_STYLE[level] ?? LEVEL_STYLE.INFO;
  const Icon = cfg.icon;
  return (
    <span className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide ${cfg.badge}`}>
      <Icon className="h-2.5 w-2.5" />
      {level}
    </span>
  );
}

function StatPill({ level, count, active, onClick }: {
  level: LogLevel; count: number; active: boolean; onClick: () => void;
}) {
  const cfg = LEVEL_STYLE[level];
  const Icon = cfg.icon;
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold transition-all select-none ${
        active
          ? cfg.activeBtnCls + " border-transparent"
          : "border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:border-slate-300"
      }`}
    >
      <Icon className="h-3 w-3" />
      {level === "ALL" ? "All" : level}
      <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-mono tabular-nums ${
        active ? "bg-white/20 text-inherit" : "bg-slate-100 text-slate-500"
      }`}>{count}</span>
    </button>
  );
}

function LogRow({ entry, idx, search }: { entry: LogEntry; idx: number; search: string }) {
  const [open, setOpen] = useState(false);
  const cfg = LEVEL_STYLE[entry.level] ?? LEVEL_STYLE.INFO;

  return (
    <>
      <tr
        className={`group font-mono text-[11.5px] leading-5 transition-colors hover:bg-slate-50 cursor-default ${cfg.row}`}
        onClick={() => entry.ex && setOpen(o => !o)}
      >
        {/* Line # */}
        <td className="select-none pl-3 pr-2 py-1.5 text-right text-[10px] tabular-nums text-slate-300 w-10">
          {idx + 1}
        </td>

        {/* Timestamp */}
        <td className="pr-3 py-1.5 whitespace-nowrap tabular-nums text-slate-400 text-[11px] w-24"
          title={formatTs(entry.ts, true)}>
          {formatTs(entry.ts)}
        </td>

        {/* Level badge */}
        <td className="pr-3 py-1.5 w-20">
          <LevelBadge level={entry.level} />
        </td>

        {/* Logger */}
        <td className="pr-3 py-1.5 max-w-[180px] truncate text-violet-600/80 text-[10.5px]"
          title={entry.logger}>
          {shortLogger(entry.logger)}
        </td>

        {/* Message */}
        <td className="pr-4 py-1.5 text-slate-700">
          <span className={cfg.text + " font-medium"}>
            {highlight(entry.msg, search)}
          </span>
          {entry.ex && (
            <span className="ml-2 inline-flex items-center gap-0.5 text-[10px] text-rose-500 opacity-70">
              {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              exception
            </span>
          )}
        </td>
      </tr>

      {/* Exception expansion */}
      {open && entry.ex && (
        <tr className="bg-rose-50/60">
          <td colSpan={5} className="px-4 pb-2 pt-0">
            <pre className="rounded-lg border border-rose-200 bg-white px-3 py-2 text-[10.5px] text-rose-700 overflow-x-auto whitespace-pre-wrap break-all leading-relaxed font-mono">
              {entry.ex}
            </pre>
          </td>
        </tr>
      )}
    </>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export function AdminLogsPage() {
  const [entries,    setEntries]    = useState<LogEntry[]>([]);
  const [level,      setLevel]      = useState<LogLevel>("ALL");
  const [search,     setSearch]     = useState("");
  const [fromTs,     setFromTs]     = useState("");
  const [toTs,       setToTs]       = useState("");
  const [autoScroll, setAutoScroll] = useState(true);
  const [connected,  setConnected]  = useState(false);
  const [showDtFilter, setShowDtFilter] = useState(false);
  const bottomRef   = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Load recent logs on mount
  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    fetch("/api/admin/logs/recent?n=200", { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(body => { setEntries((body?.data ?? []) as LogEntry[]); })
      .catch(() => {});
  }, []);

  // SSE live stream
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
        const reader  = res.body.getReader();
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
              setEntries(prev => {
                const next = [...prev, entry];
                return next.length > MAX_DISPLAYED ? next.slice(next.length - MAX_DISPLAYED) : next;
              });
            } catch { /**/ }
          }
        }
      } catch { /**/ } finally { setConnected(false); }
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
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 40) return;
    setAutoScroll(false);
  }, []);

  const filtered = entries.filter(e => passesFilter(e, level, search, fromTs, toTs));

  const counts = {
    ALL:   entries.length,
    ERROR: entries.filter(e => e.level === "ERROR").length,
    WARN:  entries.filter(e => e.level === "WARN").length,
    INFO:  entries.filter(e => e.level === "INFO").length,
    DEBUG: entries.filter(e => e.level === "DEBUG").length,
  };

  const hasDateFilter = !!(fromTs || toTs);
  const hasAnyFilter  = !!(search || hasDateFilter || level !== "ALL");

  return (
    <div className="flex h-full flex-col bg-slate-50 overflow-hidden">

      {/* ── Header ──────────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-4">
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-lg font-bold tracking-tight text-slate-900">Live Logs</h1>
            <p className="mt-0.5 text-xs text-slate-500">
              Real-time application log stream · last {MAX_DISPLAYED} entries retained
            </p>
          </div>

          <div className="flex items-center gap-2">
            {/* Entry count */}
            <span className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs font-mono font-semibold text-slate-500 tabular-nums">
              {filtered.length} / {entries.length} lines
            </span>

            {/* Connection badge */}
            <div className={`flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[11px] font-semibold ${
              connected
                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                : "border-slate-200 bg-slate-100 text-slate-400"
            }`}>
              {connected
                ? <><Wifi className="h-3 w-3" /><span className="animate-pulse">LIVE</span></>
                : <><WifiOff className="h-3 w-3" />OFFLINE</>}
            </div>
          </div>
        </div>
      </div>

      {/* ── Stats / Level filter pills ──────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-3">
        <div className="flex flex-wrap items-center gap-2">
          {LEVELS.map(l => (
            <StatPill key={l} level={l} count={counts[l]} active={level === l}
              onClick={() => { setLevel(l); }} />
          ))}

          <div className="ml-auto flex items-center gap-2">
            {hasAnyFilter && (
              <button type="button"
                onClick={() => { setSearch(""); setFromTs(""); setToTs(""); setLevel("ALL"); }}
                className="flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-semibold text-rose-600 hover:bg-rose-100 transition-colors">
                <X className="h-3 w-3" /> Clear filters
              </button>
            )}
            <button type="button" onClick={() => setEntries([])}
              className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-500 hover:bg-slate-50 transition-colors">
              <Trash2 className="h-3 w-3" /> Clear
            </button>
          </div>
        </div>
      </div>

      {/* ── Search + datetime filter bar ────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-2.5">
        <div className="flex items-center gap-2">

          {/* Search input */}
          <div className="flex flex-1 items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 focus-within:border-blue-400 focus-within:bg-white focus-within:ring-1 focus-within:ring-blue-100 transition-all">
            <Search className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <input
              type="text"
              placeholder="Search message, logger, exception…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="w-full bg-transparent text-xs text-slate-800 outline-none placeholder:text-slate-400 font-mono"
            />
            {search && (
              <button type="button" onClick={() => setSearch("")}
                className="shrink-0 text-slate-400 hover:text-slate-600 transition-colors">
                <XCircle className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          {/* DateTime filter toggle */}
          <button type="button"
            onClick={() => setShowDtFilter(v => !v)}
            className={`flex items-center gap-1.5 rounded-lg border px-3 py-2 text-xs font-semibold transition-colors ${
              showDtFilter || hasDateFilter
                ? "border-blue-300 bg-blue-50 text-blue-700"
                : "border-slate-200 bg-white text-slate-500 hover:bg-slate-50"
            }`}>
            <Filter className="h-3.5 w-3.5" />
            {hasDateFilter ? "Date filter active" : "Date / Time"}
            {hasDateFilter && (
              <span className="ml-0.5 h-2 w-2 rounded-full bg-blue-500" />
            )}
          </button>
        </div>

        {/* Datetime range row (collapsible) */}
        {showDtFilter && (
          <div className="mt-2.5 flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
            <Clock className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <span className="text-xs font-semibold text-slate-500">From</span>
            <input
              type="datetime-local"
              value={fromTs}
              onChange={e => setFromTs(e.target.value)}
              className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700 outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-100 transition-colors"
            />
            <span className="text-xs font-semibold text-slate-500">To</span>
            <input
              type="datetime-local"
              value={toTs}
              onChange={e => setToTs(e.target.value)}
              className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700 outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-100 transition-colors"
            />
            {hasDateFilter && (
              <button type="button" onClick={() => { setFromTs(""); setToTs(""); }}
                className="text-xs text-rose-500 hover:text-rose-700 font-medium transition-colors">
                Clear dates
              </button>
            )}
            <span className="ml-auto text-[11px] text-slate-400 tabular-nums font-mono">
              {filtered.length} match
            </span>
          </div>
        )}
      </div>

      {/* ── Log table ────────────────────────────────────────────────────────── */}
      <div className="relative flex-1 min-h-0 bg-white">
        <div
          ref={containerRef}
          onScroll={handleScroll}
          className="h-full overflow-y-auto"
        >
          {filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-2 text-slate-400">
              <Activity className="h-8 w-8 opacity-30" />
              <span className="text-sm font-medium">
                {entries.length === 0 ? "Waiting for log entries…" : "No entries match current filter"}
              </span>
              {entries.length > 0 && (
                <span className="text-xs">
                  {entries.length} entries buffered · adjust filters above
                </span>
              )}
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead className="sticky top-0 z-10 bg-slate-50 border-b border-slate-200">
                <tr>
                  <th className="py-2 pl-3 pr-2 text-right text-[10px] font-semibold uppercase tracking-widest text-slate-300 w-10">#</th>
                  <th className="py-2 pr-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-400 w-24">Time</th>
                  <th className="py-2 pr-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-400 w-20">Level</th>
                  <th className="py-2 pr-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-400 w-44">Logger</th>
                  <th className="py-2 pr-4 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-400">Message</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map((entry, i) => (
                  <LogRow key={i} entry={entry} idx={i} search={search} />
                ))}
              </tbody>
            </table>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Scroll-to-bottom FAB */}
        {!autoScroll && (
          <button type="button"
            onClick={() => { setAutoScroll(true); bottomRef.current?.scrollIntoView({ behavior: "smooth" }); }}
            className="absolute bottom-5 right-5 flex items-center gap-1.5 rounded-full border border-blue-200 bg-blue-600 px-4 py-2 text-xs font-semibold text-white shadow-lg shadow-blue-200 hover:bg-blue-700 transition-colors">
            <ChevronDown className="h-3.5 w-3.5" />
            Jump to latest
          </button>
        )}
      </div>
    </div>
  );
}
