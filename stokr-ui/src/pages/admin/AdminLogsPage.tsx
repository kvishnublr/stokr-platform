import { useCallback, useEffect, useRef, useState } from "react";
import { fmtDateTime, fmtTime } from "../../lib/dateUtils";
import {
  AlertTriangle, CheckCircle2, ChevronDown, ChevronRight,
  Info, Search, XCircle, WifiOff, Trash2,
  Bug, Activity, Clock, Filter, X, Terminal, Zap,
} from "lucide-react";
import { AdminPageShell, AdminPanel } from "../../components/admin/institutional/AdminDesignSystem";
import { useUiThemeStore } from "../../state/uiTheme";

type LogLevel = "ALL" | "ERROR" | "WARN" | "INFO" | "DEBUG";
type LogEntry = { ts: string; level: string; logger: string; msg: string; ex?: string };

const LEVEL_ORDER: Record<string, number> = { ERROR: 0, WARN: 1, INFO: 2, DEBUG: 3, TRACE: 4 };
const MAX_DISPLAYED = 1000;
const LEVELS: LogLevel[] = ["ALL", "ERROR", "WARN", "INFO", "DEBUG"];

const LEVEL_CFG: Record<string, {
  dot: string; text: string; badge: string; rowBg: string; border: string;
  btn: string; btnActive: string; icon: React.ElementType;
}> = {
  ERROR: {
    dot:       "bg-red-500",
    text:      "text-red-400",
    badge:     "bg-red-500/15 text-red-400 ring-1 ring-red-500/30",
    rowBg:     "bg-red-950/30",
    border:    "border-l-red-500",
    btn:       "text-red-400 hover:bg-red-500/10 border-red-500/20",
    btnActive: "bg-red-500/20 text-red-300 border-red-500/40",
    icon:      XCircle,
  },
  WARN: {
    dot:       "bg-amber-400",
    text:      "text-amber-400",
    badge:     "bg-amber-500/15 text-amber-400 ring-1 ring-amber-500/30",
    rowBg:     "bg-amber-950/20",
    border:    "border-l-amber-400",
    btn:       "text-amber-400 hover:bg-amber-500/10 border-amber-500/20",
    btnActive: "bg-amber-500/20 text-amber-300 border-amber-500/40",
    icon:      AlertTriangle,
  },
  INFO: {
    dot:       "bg-sky-400",
    text:      "text-sky-300",
    badge:     "bg-sky-500/15 text-sky-400 ring-1 ring-sky-500/30",
    rowBg:     "",
    border:    "border-l-sky-500",
    btn:       "text-sky-400 hover:bg-sky-500/10 border-sky-500/20",
    btnActive: "bg-sky-500/20 text-sky-300 border-sky-500/40",
    icon:      Info,
  },
  DEBUG: {
    dot:       "bg-slate-500",
    text:      "text-slate-500",
    badge:     "bg-slate-500/15 text-slate-400 ring-1 ring-slate-500/30",
    rowBg:     "",
    border:    "border-l-slate-600",
    btn:       "text-slate-400 hover:bg-slate-500/10 border-slate-500/20",
    btnActive: "bg-slate-500/20 text-slate-300 border-slate-500/40",
    icon:      Bug,
  },
  ALL: {
    dot:       "bg-violet-400",
    text:      "text-violet-300",
    badge:     "bg-violet-500/15 text-violet-400 ring-1 ring-violet-500/30",
    rowBg:     "",
    border:    "border-l-violet-500",
    btn:       "text-violet-400 hover:bg-violet-500/10 border-violet-500/20",
    btnActive: "bg-violet-500/20 text-violet-300 border-violet-500/40",
    icon:      Activity,
  },
};

function formatTs(iso: string, full = false): string {
  try {
    return full ? fmtDateTime(iso) : fmtTime(iso);
  } catch { return iso; }
}

function shortLogger(logger: string): string {
  const parts = logger.split(".");
  if (parts.length <= 2) return logger;
  return parts.slice(-2).join(".");
}

function highlightMsg(text: string, search: string): React.ReactNode {
  // Split into key=value tokens for syntax highlighting
  const tokens: React.ReactNode[] = [];
  const kvRe = /(\w[\w.]*)(=)([^\s,]+)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let idx = 0;
  while ((m = kvRe.exec(text)) !== null) {
    const before = text.slice(last, m.index);
    if (before) tokens.push(<span key={idx++}>{applySearch(before, search)}</span>);
    tokens.push(
      <span key={idx++}>
        <span className="text-slate-400">{m[1]}</span>
        <span className="text-slate-600">=</span>
        <span className="text-emerald-400 font-medium">{applySearch(m[3], search)}</span>
      </span>
    );
    last = m.index + m[0].length;
  }
  if (last < text.length) tokens.push(<span key={idx++}>{applySearch(text.slice(last), search)}</span>);
  return <>{tokens}</>;
}

function applySearch(text: string, q: string): React.ReactNode {
  if (!q) return text;
  const idx = text.toLowerCase().indexOf(q.toLowerCase());
  if (idx < 0) return text;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="rounded bg-yellow-400/30 text-yellow-200 px-0.5">{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  );
}

function passesFilter(entry: LogEntry, level: LogLevel, search: string, fromTs: string, toTs: string): boolean {
  if (level !== "ALL") {
    if ((LEVEL_ORDER[entry.level] ?? 99) > (LEVEL_ORDER[level] ?? 99)) return false;
  }
  if (fromTs) { try { if (new Date(entry.ts) < new Date(fromTs)) return false; } catch { /**/ } }
  if (toTs)   { try { if (new Date(entry.ts) > new Date(toTs))   return false; } catch { /**/ } }
  if (search) {
    const q = search.toLowerCase();
    return entry.msg.toLowerCase().includes(q) || entry.logger.toLowerCase().includes(q) || (entry.ex?.toLowerCase().includes(q) ?? false);
  }
  return true;
}

// ── Stat pill ─────────────────────────────────────────────────────────────────

function StatPill({ level, count, active, onClick }: { level: LogLevel; count: number; active: boolean; onClick: () => void }) {
  const cfg = LEVEL_CFG[level];
  const Icon = cfg.icon;
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs font-semibold transition-all select-none ${active ? cfg.btnActive : cfg.btn + " border-transparent"}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dot}`} />
      <Icon className="h-3 w-3" />
      {level === "ALL" ? "All" : level}
      <span className={`rounded px-1.5 py-0.5 text-[10px] font-mono tabular-nums ${active ? "bg-white/10" : "bg-white/5 text-slate-500"}`}>
        {count}
      </span>
    </button>
  );
}

// ── Log row ───────────────────────────────────────────────────────────────────

function LogRow({ entry, idx, search }: { entry: LogEntry; idx: number; search: string }) {
  const [open, setOpen] = useState(false);
  const cfg = LEVEL_CFG[entry.level] ?? LEVEL_CFG.INFO;

  return (
    <>
      <tr
        className={`group border-l-2 ${cfg.border} ${cfg.rowBg} hover:bg-white/5 transition-colors cursor-default`}
        onClick={() => entry.ex && setOpen(o => !o)}
      >
        {/* Line # */}
        <td className="select-none pl-3 pr-2 py-1 text-right text-[10px] tabular-nums text-slate-700 w-10 font-mono">
          {idx + 1}
        </td>

        {/* Timestamp */}
        <td className="pr-4 py-1 whitespace-nowrap tabular-nums text-slate-500 text-[11px] w-24 font-mono" title={formatTs(entry.ts, true)}>
          {formatTs(entry.ts)}
        </td>

        {/* Level */}
        <td className="pr-3 py-1 w-16">
          <span className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider font-mono ${cfg.badge}`}>
            {entry.level}
          </span>
        </td>

        {/* Logger */}
        <td className="pr-4 py-1 w-48 font-mono text-[10.5px] text-violet-400/70 truncate max-w-[12rem]" title={entry.logger}>
          {shortLogger(entry.logger)}
        </td>

        {/* Message */}
        <td className="pr-4 py-1 font-mono text-[11.5px]">
          <span className={`${cfg.text}`}>
            {highlightMsg(entry.msg, search)}
          </span>
          {entry.ex && (
            <span className="ml-3 inline-flex items-center gap-0.5 text-[10px] text-rose-500/70 hover:text-rose-400 transition-colors">
              {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              stack trace
            </span>
          )}
        </td>
      </tr>

      {open && entry.ex && (
        <tr className={cfg.rowBg}>
          <td colSpan={5} className="px-4 pb-3 pt-1">
            <pre className="rounded-lg border border-red-900/40 bg-red-950/40 px-4 py-3 text-[10.5px] text-red-300/90 overflow-x-auto whitespace-pre-wrap break-all leading-relaxed font-mono">
              {entry.ex}
            </pre>
          </td>
        </tr>
      )}
    </>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export function AdminLogsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [entries,      setEntries]      = useState<LogEntry[]>([]);
  const [level,        setLevel]        = useState<LogLevel>("ALL");
  const [search,       setSearch]       = useState("");
  const [fromTs,       setFromTs]       = useState("");
  const [toTs,         setToTs]         = useState("");
  const [autoScroll,   setAutoScroll]   = useState(true);
  const [connected,    setConnected]    = useState(false);
  const [showDtFilter, setShowDtFilter] = useState(false);
  const bottomRef    = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    fetch("/api/admin/logs/recent?n=200", { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(body => setEntries((body?.data ?? []) as LogEntry[]))
      .catch(() => {});
  }, []);

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
    <AdminPageShell
      isLight={isLight}
      dense
      eyebrow="Operations"
      title="Live logs"
      subtitle={`Real-time stream · ${MAX_DISPLAYED} entry buffer`}
      actions={
        <>
          <span className="rounded-md border border-white/10 bg-white/5 px-2.5 py-1 text-[11px] font-mono text-slate-400 tabular-nums dark:border-white/10">
            {filtered.length}<span className="text-slate-600"> / </span>{entries.length}
          </span>
          <div
            className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-[11px] font-semibold ${
              connected
                ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-400"
                : "border-white/10 bg-white/5 text-slate-500"
            }`}
          >
            {connected ? (
              <>
                <span className="relative flex h-2 w-2">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
                </span>
                LIVE
              </>
            ) : (
              <>
                <WifiOff className="h-3 w-3" /> OFFLINE
              </>
            )}
          </div>
          <button
            type="button"
            onClick={() => setEntries([])}
            className="flex items-center gap-1.5 rounded-lg border border-white/10 bg-white/5 px-2.5 py-1.5 text-[11px] font-semibold text-slate-400 transition-colors hover:bg-white/10 hover:text-slate-300"
          >
            <Trash2 className="h-3 w-3" /> Clear
          </button>
        </>
      }
    >
      <AdminPanel isLight={isLight} noPadding className="flex min-h-[520px] flex-1 flex-col overflow-hidden p-0">
        <div className="flex h-full min-h-[520px] flex-col overflow-hidden bg-zinc-950 text-foreground">
      <div className="shrink-0 border-b border-white/5 bg-zinc-900/60 px-6 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          {LEVELS.map(l => (
            <StatPill key={l} level={l} count={counts[l]} active={level === l} onClick={() => setLevel(l)} />
          ))}
          <div className="ml-auto flex items-center gap-2">
            {hasAnyFilter && (
              <button type="button"
                onClick={() => { setSearch(""); setFromTs(""); setToTs(""); setLevel("ALL"); }}
                className="flex items-center gap-1 rounded-lg border border-rose-500/20 bg-rose-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-rose-400 hover:bg-rose-500/20 transition-colors">
                <X className="h-3 w-3" /> Clear filters
              </button>
            )}
          </div>
        </div>
      </div>

      {/* ── Search + datetime ── */}
      <div className="shrink-0 border-b border-white/5 bg-zinc-900/40 px-6 py-2.5">
        <div className="flex items-center gap-2">
          <div className="flex flex-1 items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-3 py-2 focus-within:border-violet-500/50 focus-within:bg-violet-500/5 transition-all">
            <Search className="h-3.5 w-3.5 shrink-0 text-slate-500" />
            <input
              type="text"
              placeholder="Search message, logger, exception…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="w-full bg-transparent text-xs text-slate-300 outline-none placeholder:text-slate-600 font-mono"
            />
            {search && (
              <button type="button" onClick={() => setSearch("")} className="shrink-0 text-slate-500 hover:text-slate-300 transition-colors">
                <XCircle className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          <button type="button"
            onClick={() => setShowDtFilter(v => !v)}
            className={`flex items-center gap-1.5 rounded-lg border px-3 py-2 text-[11px] font-semibold transition-colors ${
              showDtFilter || hasDateFilter
                ? "border-violet-500/40 bg-violet-500/15 text-violet-300"
                : "border-white/10 bg-white/5 text-slate-400 hover:bg-white/10"
            }`}>
            <Clock className="h-3.5 w-3.5" />
            {hasDateFilter ? "Date active" : "Date / Time"}
            {hasDateFilter && <span className="h-1.5 w-1.5 rounded-full bg-violet-400" />}
          </button>
        </div>

        {showDtFilter && (
          <div className="mt-2 flex flex-wrap items-center gap-3 rounded-lg border border-white/8 bg-white/3 px-4 py-2.5">
            <Filter className="h-3.5 w-3.5 text-slate-500" />
            <span className="text-[11px] font-semibold text-slate-500 font-mono">FROM</span>
            <input type="datetime-local" value={fromTs} onChange={e => setFromTs(e.target.value)}
              className="rounded-md border border-white/10 bg-white/5 px-2.5 py-1.5 text-[11px] text-slate-300 font-mono outline-none focus:border-violet-500/50 transition-colors" />
            <span className="text-[11px] font-semibold text-slate-500 font-mono">TO</span>
            <input type="datetime-local" value={toTs} onChange={e => setToTs(e.target.value)}
              className="rounded-md border border-white/10 bg-white/5 px-2.5 py-1.5 text-[11px] text-slate-300 font-mono outline-none focus:border-violet-500/50 transition-colors" />
            {hasDateFilter && (
              <button type="button" onClick={() => { setFromTs(""); setToTs(""); }}
                className="text-[11px] text-rose-400 hover:text-rose-300 font-medium transition-colors">
                Clear dates
              </button>
            )}
            <span className="ml-auto text-[10px] text-slate-600 tabular-nums font-mono">{filtered.length} match</span>
          </div>
        )}
      </div>

      {/* ── Log stream ── */}
      <div className="relative flex-1 min-h-0">
        <div ref={containerRef} onScroll={handleScroll} className="h-full overflow-y-auto">
          {filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-52 gap-3 text-slate-600">
              <Zap className="h-10 w-10 opacity-20" />
              <span className="text-sm font-mono font-medium">
                {entries.length === 0 ? "₹ waiting for log stream..." : "no entries match filter"}
              </span>
              {entries.length > 0 && (
                <span className="text-xs font-mono text-slate-700">{entries.length} entries buffered · adjust filters above</span>
              )}
            </div>
          ) : (
            <table className="w-full border-collapse">
              <thead className="sticky top-0 z-10 bg-zinc-900/95 backdrop-blur border-b border-white/5">
                <tr>
                  <th className="py-2 pl-3 pr-2 text-right text-[9px] font-semibold uppercase tracking-widest text-slate-700 w-10 font-mono">#</th>
                  <th className="py-2 pr-4 text-left text-[9px] font-semibold uppercase tracking-widest text-slate-600 w-24 font-mono">Time</th>
                  <th className="py-2 pr-3 text-left text-[9px] font-semibold uppercase tracking-widest text-slate-600 w-16 font-mono">Level</th>
                  <th className="py-2 pr-4 text-left text-[9px] font-semibold uppercase tracking-widest text-slate-600 w-48 font-mono">Logger</th>
                  <th className="py-2 pr-4 text-left text-[9px] font-semibold uppercase tracking-widest text-slate-600 font-mono">Message</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/3">
                {filtered.map((entry, i) => (
                  <LogRow key={i} entry={entry} idx={i} search={search} />
                ))}
              </tbody>
            </table>
          )}
          <div ref={bottomRef} />
        </div>

        {!autoScroll && (
          <button type="button"
            onClick={() => { setAutoScroll(true); bottomRef.current?.scrollIntoView({ behavior: "smooth" }); }}
            className="absolute bottom-5 right-5 flex items-center gap-1.5 rounded-full border border-violet-500/30 bg-violet-600/90 px-4 py-2 text-xs font-semibold text-white shadow-lg shadow-violet-900/50 hover:bg-violet-500 transition-colors backdrop-blur">
            <ChevronDown className="h-3.5 w-3.5" />
            Jump to latest
          </button>
        )}
      </div>
        </div>
      </AdminPanel>
    </AdminPageShell>
  );
}
