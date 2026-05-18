import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import {
  bulkImportSymbols,
  createUniverseGroup,
  fetchGroupSymbols,
  fetchUniverseGroups,
  syncUniverseGroup,
  toggleUniverseGroup,
  type UniverseGroupDto,
  type UniverseSymbolDto,
} from "../../api/strategyCatalog";
import { parseAxiosMessage } from "../../api/client";
import { cn } from "../../lib/utils";
import { ChevronDown, ChevronUp, Database, Plus, RefreshCw } from "lucide-react";

const ASSET_CLASSES = ["EQUITY", "COMMODITY", "FUTURES", "OPTIONS", "CURRENCY"];
const SEGMENTS = ["NSE", "NFO", "MCX", "CDS", "BSE"];
const INSTRUMENT_TYPES = ["EQ", "FUT", "OPT", "COM", "CUR"];
const UNIVERSE_TYPES = ["INDEX_CONSTITUENTS", "CUSTOM", "SECTOR", "FUTURES", "OPTIONS", "COMMODITY"];

const ASSET_COLORS: Record<string, string> = {
  EQUITY: "text-blue-300 bg-blue-900/30",
  COMMODITY: "text-amber-300 bg-amber-900/30",
  FUTURES: "text-purple-300 bg-purple-900/30",
  OPTIONS: "text-pink-300 bg-pink-900/30",
  CURRENCY: "text-teal-300 bg-teal-900/30",
};

export function AdminUniverseGroupsPage() {
  const qc = useQueryClient();
  const [assetFilter, setAssetFilter] = useState("");
  const [showCreate, setShowCreate] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [importText, setImportText] = useState("");
  const [form, setForm] = useState({
    groupKey: "",
    displayName: "",
    description: "",
    universeType: "CUSTOM",
    exchange: "NSE",
    assetClass: "EQUITY",
    segment: "NSE",
    instrumentType: "EQ",
    autoManaged: false,
  });

  const q = useQuery({
    queryKey: ["admin-universe-groups", assetFilter],
    queryFn: () => fetchUniverseGroups(assetFilter || undefined, 0, 100),
  });

  const symbolQ = useQuery({
    queryKey: ["admin-universe-symbols", expandedId],
    queryFn: () => fetchGroupSymbols(expandedId!),
    enabled: !!expandedId,
  });

  const createMut = useMutation({
    mutationFn: createUniverseGroup,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-universe-groups"] });
      toast.success("Universe group created");
      setShowCreate(false);
      setForm((f) => ({ ...f, groupKey: "", displayName: "", description: "" }));
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const toggleMut = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => toggleUniverseGroup(id, enabled),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["admin-universe-groups"] }),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const syncMut = useMutation({
    mutationFn: syncUniverseGroup,
    onSuccess: (data, id) => {
      void qc.invalidateQueries({ queryKey: ["admin-universe-groups"] });
      void qc.invalidateQueries({ queryKey: ["admin-universe-symbols", id] });
      toast.success(`Synced ${data.synced} symbols`);
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const importMut = useMutation({
    mutationFn: ({ groupId, symbols }: { groupId: string; symbols: { symbol: string }[] }) =>
      bulkImportSymbols(groupId, symbols, true),
    onSuccess: (data, { groupId }) => {
      void qc.invalidateQueries({ queryKey: ["admin-universe-symbols", groupId] });
      void qc.invalidateQueries({ queryKey: ["admin-universe-groups"] });
      toast.success(`Imported ${data.imported} symbols`);
      setImportText("");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const handleImport = (groupId: string) => {
    const symbols = importText
      .split(/[\n,]+/)
      .map((s) => s.trim().toUpperCase())
      .filter(Boolean)
      .map((symbol) => ({ symbol }));
    if (!symbols.length) {
      toast.error("Enter at least one symbol");
      return;
    }
    importMut.mutate({ groupId, symbols });
  };

  return (
    <div className="space-y-4 text-foreground">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Universe groups</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Define symbol universes across equity, commodity, futures, and options. Bind them to strategies on the Runtime Bindings page.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="flex items-center gap-1.5 rounded-lg bg-violet-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-violet-500"
        >
          <Plus className="h-3.5 w-3.5" />
          New group
        </button>
      </div>

      {/* Asset filter pills */}
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
          <p className="text-sm font-semibold text-violet-300">New universe group</p>
          <div className="grid gap-2 sm:grid-cols-2">
            <input
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="GROUP_KEY (UPPER_SNAKE_CASE)"
              value={form.groupKey}
              onChange={(e) => setForm((f) => ({ ...f, groupKey: e.target.value.toUpperCase() }))}
            />
            <input
              className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="Display name"
              value={form.displayName}
              onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
            />
            <input
              className="col-span-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-500"
              placeholder="Description (optional)"
              value={form.description}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
            />
            <select className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white" value={form.assetClass} onChange={(e) => setForm((f) => ({ ...f, assetClass: e.target.value }))}>
              {ASSET_CLASSES.map((a) => <option key={a}>{a}</option>)}
            </select>
            <select className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white" value={form.segment} onChange={(e) => setForm((f) => ({ ...f, segment: e.target.value }))}>
              {SEGMENTS.map((s) => <option key={s}>{s}</option>)}
            </select>
            <select className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white" value={form.instrumentType} onChange={(e) => setForm((f) => ({ ...f, instrumentType: e.target.value }))}>
              {INSTRUMENT_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
            <select className="rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white" value={form.universeType} onChange={(e) => setForm((f) => ({ ...f, universeType: e.target.value }))}>
              {UNIVERSE_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </div>
          <label className="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer">
            <input type="checkbox" checked={form.autoManaged} onChange={(e) => setForm((f) => ({ ...f, autoManaged: e.target.checked }))} className="accent-violet-500" />
            Auto-managed (eligible for symbol sync)
          </label>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!form.groupKey || !form.displayName || createMut.isPending}
              onClick={() => createMut.mutate(form)}
              className="rounded-lg bg-violet-600 px-4 py-1.5 text-xs font-semibold text-white disabled:opacity-50 hover:bg-violet-500"
            >
              {createMut.isPending ? "Creating…" : "Create group"}
            </button>
            <button type="button" onClick={() => setShowCreate(false)} className="rounded-lg bg-neutral-800 px-4 py-1.5 text-xs font-semibold text-neutral-300">Cancel</button>
          </div>
        </div>
      )}

      {/* Group list */}
      {q.isLoading ? (
        <div className="grid gap-3 lg:grid-cols-2">
          {[1, 2, 3].map((i) => <div key={i} className="h-24 animate-pulse rounded-xl border border-neutral-800 bg-neutral-900/40" />)}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-900/40 bg-red-950/30 px-4 py-3 text-sm text-red-300">Failed. {parseAxiosMessage(q.error)}</div>
      ) : (q.data?.content ?? []).length === 0 ? (
        <div className="rounded-xl border border-neutral-800 px-4 py-8 text-center text-sm text-neutral-500">No universe groups. Create one above.</div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {(q.data?.content ?? []).map((g) => (
            <UniverseGroupCard
              key={g.id}
              group={g}
              expanded={expandedId === g.id}
              onToggleExpand={() => setExpandedId(expandedId === g.id ? null : g.id)}
              onToggleEnabled={() => toggleMut.mutate({ id: g.id, enabled: !g.enabled })}
              onSync={() => syncMut.mutate(g.id)}
              symbols={expandedId === g.id ? (symbolQ.data ?? []) : []}
              importText={expandedId === g.id ? importText : ""}
              setImportText={expandedId === g.id ? setImportText : () => {}}
              onImport={() => handleImport(g.id)}
              importing={importMut.isPending}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function UniverseGroupCard({
  group: g,
  expanded,
  onToggleExpand,
  onToggleEnabled,
  onSync,
  symbols,
  importText,
  setImportText,
  onImport,
  importing,
}: {
  group: UniverseGroupDto;
  expanded: boolean;
  onToggleExpand: () => void;
  onToggleEnabled: () => void;
  onSync: () => void;
  symbols: UniverseSymbolDto[];
  importText: string;
  setImportText: (v: string) => void;
  onImport: () => void;
  importing: boolean;
}) {
  const assetColor = ASSET_COLORS[g.assetClass] ?? "text-neutral-300 bg-neutral-800";
  return (
    <div className="rounded-xl border border-neutral-800 bg-neutral-900/40 p-4 space-y-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Database className="h-4 w-4 shrink-0 text-violet-400" />
            <span className="font-semibold text-white truncate">{g.displayName}</span>
            <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold uppercase", assetColor)}>{g.assetClass}</span>
            <span className="rounded-full bg-neutral-800 px-2 py-0.5 text-[10px] font-bold text-neutral-400">{g.instrumentType}</span>
          </div>
          <div className="mt-0.5 font-mono text-[11px] text-neutral-500">{g.groupKey}</div>
        </div>
        <button type="button" onClick={onToggleExpand} className="text-neutral-500 hover:text-neutral-300">
          {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-3 text-xs text-neutral-400">
        <span>{g.symbolCount} symbols</span>
        <span>{g.segment}</span>
        <span>{g.universeType}</span>
        {g.autoManaged && <span className="text-emerald-400">auto-managed</span>}
      </div>

      {expanded && (
        <div className="space-y-3">
          {/* Symbol list */}
          {symbols.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {symbols.slice(0, 60).map((s) => (
                <span key={s.id} className="rounded bg-neutral-800 px-1.5 py-0.5 font-mono text-[10px] text-neutral-300">
                  {s.symbol}
                </span>
              ))}
              {symbols.length > 60 && (
                <span className="rounded bg-neutral-800 px-1.5 py-0.5 text-[10px] text-neutral-500">
                  +{symbols.length - 60} more
                </span>
              )}
            </div>
          )}
          {/* Import */}
          <div className="space-y-1.5">
            <p className="text-[11px] text-neutral-500">Bulk import symbols (comma or newline separated):</p>
            <textarea
              rows={3}
              className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 font-mono text-xs text-white placeholder:text-neutral-600"
              placeholder="RELIANCE, TCS, INFY..."
              value={importText}
              onChange={(e) => setImportText(e.target.value)}
            />
            <button
              type="button"
              disabled={importing || !importText.trim()}
              onClick={onImport}
              className="rounded-lg bg-violet-700/40 px-3 py-1 text-[11px] font-semibold text-violet-300 disabled:opacity-50 hover:bg-violet-600/40"
            >
              {importing ? "Importing…" : "Import & replace"}
            </button>
          </div>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onToggleEnabled}
          className={cn(
            "rounded-lg px-2.5 py-1 text-[11px] font-semibold",
            g.enabled ? "bg-emerald-700 text-white" : "bg-neutral-800 text-neutral-400",
          )}
        >
          {g.enabled ? "Enabled" : "Disabled"}
        </button>
        {g.autoManaged && (
          <button
            type="button"
            onClick={onSync}
            className="flex items-center gap-1 rounded-lg bg-neutral-800 px-2.5 py-1 text-[11px] font-semibold text-neutral-300 hover:bg-neutral-700"
          >
            <RefreshCw className="h-3 w-3" />
            Sync symbols
          </button>
        )}
      </div>
    </div>
  );
}
