import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import {
  createStrategy,
  deleteStrategy,
  fetchStrategyCatalog,
  generateStrategyTemplate,
  patchStrategy,
  type AdminStrategyDto,
} from "../../api/strategyCatalog";
import { parseAxiosMessage } from "../../api/client";
import { cn } from "../../lib/utils";
import {
  ChevronDown,
  ChevronUp,
  Code2,
  Eye,
  EyeOff,
  Layers,
  Plus,
  RefreshCw,
  Trash2,
} from "lucide-react";
import { SymbolMultiSelect } from "../../components/ds/SymbolMultiSelect";

const ASSET_CLASSES = ["EQUITY", "COMMODITY", "FUTURES", "OPTIONS", "CURRENCY"];
const SEGMENTS = ["NSE", "NFO", "MCX", "CDS", "BSE"];
const STRATEGY_TYPES = ["INTRADAY", "SWING", "POSITIONAL", "SCALPING"];
const TIMEFRAMES = ["1m", "3m", "5m", "10m", "15m", "30m", "1h", "1d"];

const ASSET_COLORS: Record<string, string> = {
  EQUITY: "bg-blue-500/15 text-blue-600 dark:text-blue-300",
  COMMODITY: "bg-amber-500/15 text-amber-600 dark:text-amber-300",
  FUTURES: "bg-purple-500/15 text-purple-600 dark:text-purple-300",
  OPTIONS: "bg-pink-500/15 text-pink-600 dark:text-pink-300",
  CURRENCY: "bg-teal-500/15 text-teal-600 dark:text-teal-300",
};

const inputCls =
  "rounded-lg border border-border bg-background px-3 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-violet-500";

export function AdminStrategyCatalogPage() {
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [assetFilter, setAssetFilter] = useState("");
  const [form, setForm] = useState({
    strategyKey: "",
    displayName: "",
    description: "",
    strategyType: "INTRADAY",
    executionMode: "ALL",
    assetClass: "EQUITY",
    segment: "NSE",
    defaultTimeframe: "1m",
    defaultExchange: "NSE",
    riskLevel: "MEDIUM",
    generateTemplate: true,
    derivativeEnabled: false,
    futuresStrategyEnabled: false,
    optionStrategyEnabled: false,
    symbols: [] as string[],
  });

  const q = useQuery({
    queryKey: ["admin-strategy-catalog"],
    queryFn: () => fetchStrategyCatalog(0, 100),
  });

  const createMut = useMutation({
    mutationFn: createStrategy,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-strategy-catalog"] });
      toast.success("Strategy created");
      setShowCreate(false);
      setForm((f) => ({ ...f, strategyKey: "", displayName: "", description: "" }));
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const patchMut = useMutation({
    mutationFn: ({ id, body }: { id: string; body: Record<string, unknown> }) => patchStrategy(id, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["admin-strategy-catalog"] }),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const genMut = useMutation({
    mutationFn: generateStrategyTemplate,
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ["admin-strategy-catalog"] });
      toast.success(`Template generated: ${d.templateClassName}`);
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const deleteMut = useMutation({
    mutationFn: deleteStrategy,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-strategy-catalog"] });
      toast.success("Strategy deleted");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const filtered = (q.data?.content ?? []).filter(
    (s) => !assetFilter || s.assetClass === assetFilter,
  );

  return (
    <div className="space-y-4 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Strategy catalog</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Create and manage admin-owned strategy definitions. Bind them to universes on the Runtime Bindings page.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="flex items-center gap-1.5 rounded-lg bg-violet-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-violet-500"
        >
          <Plus className="h-3.5 w-3.5" />
          New strategy
        </button>
      </div>

      {/* Asset class filter */}
      <div className="flex flex-wrap gap-2">
        {["", ...ASSET_CLASSES].map((ac) => (
          <button
            key={ac}
            type="button"
            onClick={() => setAssetFilter(ac)}
            className={cn(
              "rounded-full px-3 py-0.5 text-[11px] font-semibold transition-colors",
              assetFilter === ac
                ? "bg-violet-600 text-white"
                : "bg-muted text-muted-foreground hover:bg-muted/70",
            )}
          >
            {ac || "All"}
          </button>
        ))}
      </div>

      {/* Create form */}
      {showCreate && (
        <div className="rounded-xl border border-border bg-card p-4 space-y-3 shadow-sm">
          <p className="text-sm font-semibold text-violet-500">New strategy</p>
          <div className="grid gap-2 sm:grid-cols-2">
            <input
              className={cn(inputCls, "col-span-full")}
              placeholder="STRATEGY_KEY (UPPER_SNAKE_CASE)"
              value={form.strategyKey}
              onChange={(e) => setForm((f) => ({ ...f, strategyKey: e.target.value.toUpperCase() }))}
            />
            <input
              className={inputCls}
              placeholder="Display name"
              value={form.displayName}
              onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
            />
            <input
              className={inputCls}
              placeholder="Description (optional)"
              value={form.description}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
            />
            <select
              className={inputCls}
              value={form.assetClass}
              onChange={(e) => setForm((f) => ({ ...f, assetClass: e.target.value }))}
            >
              {ASSET_CLASSES.map((a) => <option key={a}>{a}</option>)}
            </select>
            <select
              className={inputCls}
              value={form.segment}
              onChange={(e) => setForm((f) => ({ ...f, segment: e.target.value }))}
            >
              {SEGMENTS.map((s) => <option key={s}>{s}</option>)}
            </select>
            <select
              className={inputCls}
              value={form.strategyType}
              onChange={(e) => setForm((f) => ({ ...f, strategyType: e.target.value }))}
            >
              {STRATEGY_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
            <select
              className={inputCls}
              value={form.defaultTimeframe}
              onChange={(e) => setForm((f) => ({ ...f, defaultTimeframe: e.target.value }))}
            >
              {TIMEFRAMES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <p className="text-[11px] text-muted-foreground">Specific symbols <span className="text-muted-foreground/60">(optional — leave empty for universe-wide)</span></p>
            <SymbolMultiSelect
              value={form.symbols}
              onChange={(v) => setForm((f) => ({ ...f, symbols: v }))}
              placeholder="Search or type symbol (e.g. RELIANCE, TCS)…"
            />
          </div>

          <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
            {(["derivativeEnabled", "futuresStrategyEnabled", "optionStrategyEnabled"] as const).map((k) => (
              <label key={k} className="flex items-center gap-1.5 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={form[k]}
                  onChange={(e) => setForm((f) => ({ ...f, [k]: e.target.checked }))}
                  className="accent-violet-500"
                />
                {k === "derivativeEnabled" ? "Derivative" : k === "futuresStrategyEnabled" ? "Futures" : "Options"}
              </label>
            ))}
            <label className="flex items-center gap-1.5 cursor-pointer select-none">
              <input
                type="checkbox"
                checked={form.generateTemplate}
                onChange={(e) => setForm((f) => ({ ...f, generateTemplate: e.target.checked }))}
                className="accent-violet-500"
              />
              Generate Java template now
            </label>
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!form.strategyKey || !form.displayName || createMut.isPending}
              onClick={() => createMut.mutate(form)}
              className="rounded-lg bg-violet-600 px-4 py-1.5 text-xs font-semibold text-white disabled:opacity-50 hover:bg-violet-500"
            >
              {createMut.isPending ? "Creating…" : "Create strategy"}
            </button>
            <button
              type="button"
              onClick={() => setShowCreate(false)}
              className="rounded-lg border border-border bg-muted px-4 py-1.5 text-xs font-semibold text-muted-foreground hover:bg-muted/70"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Strategy list */}
      {q.isLoading ? (
        <div className="grid gap-3 lg:grid-cols-2">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-28 animate-pulse rounded-xl border border-border bg-muted" />
          ))}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 dark:border-red-900/40 dark:bg-red-950/30 dark:text-red-300">
          Failed to load. {parseAxiosMessage(q.error)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="rounded-xl border border-border bg-muted/30 px-4 py-8 text-center text-sm text-muted-foreground">
          No strategies found. Create one above.
        </div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {filtered.map((s) => (
            <StrategyCard
              key={s.id}
              strategy={s}
              expanded={expandedId === s.id}
              onToggleExpand={() => setExpandedId(expandedId === s.id ? null : s.id)}
              onToggleEnabled={() => patchMut.mutate({ id: s.id, body: { enabled: !s.enabled } })}
              onGenerateTemplate={() => genMut.mutate(s.id)}
              onDelete={() => {
                if (confirm(`Delete strategy ${s.code}?`)) deleteMut.mutate(s.id);
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function StrategyCard({
  strategy: s,
  expanded,
  onToggleExpand,
  onToggleEnabled,
  onGenerateTemplate,
  onDelete,
}: {
  strategy: AdminStrategyDto;
  expanded: boolean;
  onToggleExpand: () => void;
  onToggleEnabled: () => void;
  onGenerateTemplate: () => void;
  onDelete: () => void;
}) {
  const assetColor = ASSET_COLORS[s.assetClass ?? "EQUITY"] ?? "bg-muted text-muted-foreground";
  return (
    <div className="rounded-xl border border-border bg-card p-4 space-y-3 shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Layers className="h-4 w-4 shrink-0 text-violet-500" />
            <span className="font-semibold text-foreground truncate">{s.displayName ?? s.code}</span>
            <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold uppercase", assetColor)}>
              {s.assetClass ?? "EQUITY"}
            </span>
            {s.segment && (
              <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-bold text-muted-foreground">
                {s.segment}
              </span>
            )}
          </div>
          <div className="mt-0.5 font-mono text-[11px] text-muted-foreground">{s.code}</div>
        </div>
        <button type="button" onClick={onToggleExpand} className="text-muted-foreground hover:text-foreground">
          {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>
      </div>

      {s.description && <p className="text-xs text-muted-foreground">{s.description}</p>}

      {/* Symbol chips */}
      {s.symbols && s.symbols.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {s.symbols.slice(0, 8).map((sym) => (
            <span key={sym} className="rounded-md bg-violet-500/10 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-violet-600 dark:text-violet-300">
              {sym}
            </span>
          ))}
          {s.symbols.length > 8 && (
            <span className="rounded-md bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">+{s.symbols.length - 8}</span>
          )}
        </div>
      )}

      {/* Badges row */}
      <div className="flex flex-wrap gap-1.5 text-[10px] font-semibold">
        <Badge label={s.strategyType ?? "INTRADAY"} />
        <Badge label={s.defaultTimeframe ?? "1m"} />
        {s.derivativeEnabled && <Badge label="DERIV" color="text-purple-600 bg-purple-500/15 dark:text-purple-300" />}
        {s.futuresStrategyEnabled && <Badge label="FUT" color="text-amber-600 bg-amber-500/15 dark:text-amber-300" />}
        {s.optionStrategyEnabled && <Badge label="OPT" color="text-pink-600 bg-pink-500/15 dark:text-pink-300" />}
        {s.templateGenerated && <Badge label="TEMPLATE ✓" color="text-emerald-600 bg-emerald-500/15 dark:text-emerald-300" />}
      </div>

      {/* Expanded detail */}
      {expanded && (
        <div className="rounded-lg border border-border bg-muted/40 p-3 space-y-1 text-[11px] text-muted-foreground">
          <Row label="Class" value={s.templateClassName ?? "—"} mono />
          <Row label="Path" value={s.generatedClassPath ?? "—"} mono />
          <Row label="Version" value={s.catalogVersion ?? "1.0"} />
          <Row label="Execution mode" value={s.executionMode ?? "ALL"} />
          <Row
            label="Capabilities"
            value={[
              s.supportsBacktest && "backtest",
              s.supportsPaper && "paper",
              s.supportsLive && "live",
            ]
              .filter(Boolean)
              .join(", ")}
          />
        </div>
      )}

      {/* Actions */}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onToggleEnabled}
          className={cn(
            "flex items-center gap-1 rounded-lg px-2.5 py-1 text-[11px] font-semibold",
            s.enabled
              ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
              : "bg-muted text-muted-foreground",
          )}
        >
          {s.enabled ? <Eye className="h-3 w-3" /> : <EyeOff className="h-3 w-3" />}
          {s.enabled ? "Enabled" : "Disabled"}
        </button>
        {!s.templateGenerated && (
          <button
            type="button"
            onClick={onGenerateTemplate}
            className="flex items-center gap-1 rounded-lg bg-violet-500/15 px-2.5 py-1 text-[11px] font-semibold text-violet-600 hover:bg-violet-500/25 dark:text-violet-300"
          >
            <Code2 className="h-3 w-3" />
            Generate template
          </button>
        )}
        {s.templateGenerated && (
          <button
            type="button"
            onClick={onGenerateTemplate}
            className="flex items-center gap-1 rounded-lg bg-muted px-2.5 py-1 text-[11px] font-semibold text-muted-foreground hover:bg-muted/70"
            title="Re-generate template"
          >
            <RefreshCw className="h-3 w-3" />
            Re-generate
          </button>
        )}
        <button
          type="button"
          onClick={onDelete}
          className="ml-auto flex items-center gap-1 rounded-lg bg-red-500/10 px-2.5 py-1 text-[11px] font-semibold text-red-500 hover:bg-red-500/20"
        >
          <Trash2 className="h-3 w-3" />
          Delete
        </button>
      </div>
    </div>
  );
}

function Badge({ label, color = "bg-muted text-muted-foreground" }: { label: string; color?: string }) {
  return <span className={cn("rounded-full px-2 py-0.5 uppercase", color)}>{label}</span>;
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2">
      <span className="w-28 shrink-0 text-muted-foreground">{label}</span>
      <span className={cn("truncate text-foreground", mono && "font-mono text-[10px]")}>{value}</span>
    </div>
  );
}
