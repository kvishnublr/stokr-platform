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

const ASSET_CLASSES = ["EQUITY", "COMMODITY", "FUTURES", "OPTIONS", "CURRENCY"];
const SEGMENTS = ["NSE", "NFO", "MCX", "CDS", "BSE"];
const STRATEGY_TYPES = ["INTRADAY", "SWING", "POSITIONAL", "SCALPING"];
const TIMEFRAMES = ["1m", "3m", "5m", "10m", "15m", "30m", "1h", "1d"];

const ASSET_COLORS: Record<string, string> = {
  EQUITY: "bg-blue-900/40 text-blue-300",
  COMMODITY: "bg-amber-900/40 text-amber-300",
  FUTURES: "bg-purple-900/40 text-purple-300",
  OPTIONS: "bg-pink-900/40 text-pink-300",
  CURRENCY: "bg-teal-900/40 text-teal-300",
};

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
              "rounded-full px-3 py-0.5 text-[11px] font-semibold",
              assetFilter === ac
                ? "bg-violet-600 text-white"
                : "bg-neutral-800 text-neutral-300 hover:bg-neutral-700",
            )}
          >
            {ac || "All"}
          </button>
        ))}
      </div>

      {/* Create form */}
      {showCreate && (
        <div className="rounded-xl border border-violet-800/40 bg-violet-950/20 p-4 space-y-3">
          <p className="text-sm font-semibold text-violet-300">New strategy</p>
          <div className="grid gap-2 sm:grid-cols-2">
            <input
              className="col-span-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="STRATEGY_KEY (UPPER_SNAKE_CASE)"
              value={form.strategyKey}
              onChange={(e) => setForm((f) => ({ ...f, strategyKey: e.target.value.toUpperCase() }))}
            />
            <input
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="Display name"
              value={form.displayName}
              onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
            />
            <input
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="Description (optional)"
              value={form.description}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
            />
            <select
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
              value={form.assetClass}
              onChange={(e) => setForm((f) => ({ ...f, assetClass: e.target.value }))}
            >
              {ASSET_CLASSES.map((a) => <option key={a}>{a}</option>)}
            </select>
            <select
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
              value={form.segment}
              onChange={(e) => setForm((f) => ({ ...f, segment: e.target.value }))}
            >
              {SEGMENTS.map((s) => <option key={s}>{s}</option>)}
            </select>
            <select
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
              value={form.strategyType}
              onChange={(e) => setForm((f) => ({ ...f, strategyType: e.target.value }))}
            >
              {STRATEGY_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
            <select
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
              value={form.defaultTimeframe}
              onChange={(e) => setForm((f) => ({ ...f, defaultTimeframe: e.target.value }))}
            >
              {TIMEFRAMES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </div>
          <div className="flex flex-wrap gap-3 text-xs text-neutral-300">
            {(["derivativeEnabled", "futuresStrategyEnabled", "optionStrategyEnabled"] as const).map((k) => (
              <label key={k} className="flex items-center gap-1.5 cursor-pointer">
                <input
                  type="checkbox"
                  checked={form[k]}
                  onChange={(e) => setForm((f) => ({ ...f, [k]: e.target.checked }))}
                  className="accent-violet-500"
                />
                {k === "derivativeEnabled" ? "Derivative" : k === "futuresStrategyEnabled" ? "Futures" : "Options"}
              </label>
            ))}
            <label className="flex items-center gap-1.5 cursor-pointer">
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
              className="rounded-lg bg-neutral-800 px-4 py-1.5 text-xs font-semibold text-neutral-300 hover:bg-neutral-700"
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
            <div key={i} className="h-28 animate-pulse rounded-xl border border-neutral-800 bg-neutral-900/40" />
          ))}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-900/40 bg-red-950/30 px-4 py-3 text-sm text-red-300">
          Failed to load. {parseAxiosMessage(q.error)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="rounded-xl border border-neutral-800 bg-neutral-900/20 px-4 py-8 text-center text-sm text-neutral-500">
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
  const assetColor = ASSET_COLORS[s.assetClass ?? "EQUITY"] ?? "bg-neutral-800 text-neutral-300";
  return (
    <div className="rounded-xl border border-neutral-800 bg-neutral-900/40 p-4 space-y-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Layers className="h-4 w-4 shrink-0 text-violet-400" />
            <span className="font-semibold text-white truncate">{s.displayName ?? s.code}</span>
            <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold uppercase", assetColor)}>
              {s.assetClass ?? "EQUITY"}
            </span>
            {s.segment && (
              <span className="rounded-full bg-neutral-800 px-2 py-0.5 text-[10px] font-bold text-neutral-400">
                {s.segment}
              </span>
            )}
          </div>
          <div className="mt-0.5 font-mono text-[11px] text-neutral-500">{s.code}</div>
        </div>
        <button type="button" onClick={onToggleExpand} className="text-neutral-500 hover:text-neutral-300">
          {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>
      </div>

      {s.description && <p className="text-xs text-neutral-400">{s.description}</p>}

      {/* Badges row */}
      <div className="flex flex-wrap gap-1.5 text-[10px] font-semibold">
        <Badge label={s.strategyType ?? "INTRADAY"} />
        <Badge label={s.defaultTimeframe ?? "1m"} />
        {s.derivativeEnabled && <Badge label="DERIV" color="text-purple-300 bg-purple-900/30" />}
        {s.futuresStrategyEnabled && <Badge label="FUT" color="text-amber-300 bg-amber-900/30" />}
        {s.optionStrategyEnabled && <Badge label="OPT" color="text-pink-300 bg-pink-900/30" />}
        {s.templateGenerated && <Badge label="TEMPLATE ✓" color="text-emerald-300 bg-emerald-900/30" />}
      </div>

      {/* Expanded detail */}
      {expanded && (
        <div className="rounded-lg border border-neutral-700/50 bg-neutral-900/60 p-3 space-y-1 text-[11px] text-neutral-400">
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
            s.enabled ? "bg-emerald-700 text-white" : "bg-neutral-800 text-neutral-400",
          )}
        >
          {s.enabled ? <Eye className="h-3 w-3" /> : <EyeOff className="h-3 w-3" />}
          {s.enabled ? "Enabled" : "Disabled"}
        </button>
        {!s.templateGenerated && (
          <button
            type="button"
            onClick={onGenerateTemplate}
            className="flex items-center gap-1 rounded-lg bg-violet-800/40 px-2.5 py-1 text-[11px] font-semibold text-violet-300 hover:bg-violet-700/40"
          >
            <Code2 className="h-3 w-3" />
            Generate template
          </button>
        )}
        {s.templateGenerated && (
          <button
            type="button"
            onClick={onGenerateTemplate}
            className="flex items-center gap-1 rounded-lg bg-neutral-800 px-2.5 py-1 text-[11px] font-semibold text-neutral-400 hover:bg-neutral-700"
            title="Re-generate template"
          >
            <RefreshCw className="h-3 w-3" />
            Re-generate
          </button>
        )}
        <button
          type="button"
          onClick={onDelete}
          className="ml-auto flex items-center gap-1 rounded-lg bg-red-950/30 px-2.5 py-1 text-[11px] font-semibold text-red-400 hover:bg-red-900/40"
        >
          <Trash2 className="h-3 w-3" />
          Delete
        </button>
      </div>
    </div>
  );
}

function Badge({ label, color = "bg-neutral-800 text-neutral-400" }: { label: string; color?: string }) {
  return <span className={cn("rounded-full px-2 py-0.5 uppercase", color)}>{label}</span>;
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2">
      <span className="w-28 shrink-0 text-neutral-500">{label}</span>
      <span className={cn("truncate text-neutral-300", mono && "font-mono text-[10px]")}>{value}</span>
    </div>
  );
}
