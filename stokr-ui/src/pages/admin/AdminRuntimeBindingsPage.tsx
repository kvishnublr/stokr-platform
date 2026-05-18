import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import {
  createRuntimeBinding,
  deleteRuntimeBinding,
  fetchRuntimeBindings,
  fetchStrategyCatalog,
  fetchUniverseGroups,
  toggleRuntimeBinding,
  type RuntimeBindingDto,
} from "../../api/strategyCatalog";
import { parseAxiosMessage } from "../../api/client";
import { cn } from "../../lib/utils";
import { Activity, Link, Plus, Trash2, Zap } from "lucide-react";

const RISK_PROFILES = ["LOW", "MEDIUM", "HIGH"];

export function AdminRuntimeBindingsPage() {
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({
    strategyCatalogId: "",
    universeGroupId: "",
    runtimeEnabled: false,
    maxPositions: 5,
    scanIntervalSeconds: 60,
    riskProfile: "MEDIUM",
    capitalLimit: "",
  });

  const bindingsQ = useQuery({
    queryKey: ["admin-runtime-bindings"],
    queryFn: () => fetchRuntimeBindings(0, 100),
  });

  const strategiesQ = useQuery({
    queryKey: ["admin-strategy-catalog"],
    queryFn: () => fetchStrategyCatalog(0, 100),
  });

  const groupsQ = useQuery({
    queryKey: ["admin-universe-groups", ""],
    queryFn: () => fetchUniverseGroups(undefined, 0, 100),
  });

  const createMut = useMutation({
    mutationFn: createRuntimeBinding,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-runtime-bindings"] });
      toast.success("Runtime binding created");
      setShowCreate(false);
      setForm((f) => ({ ...f, strategyCatalogId: "", universeGroupId: "" }));
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const toggleMut = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => toggleRuntimeBinding(id, enabled),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["admin-runtime-bindings"] }),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const deleteMut = useMutation({
    mutationFn: deleteRuntimeBinding,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-runtime-bindings"] });
      toast.success("Binding removed");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const bindings = bindingsQ.data?.content ?? [];
  const activeCount = bindings.filter((b) => b.runtimeEnabled).length;

  return (
    <div className="space-y-4 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Runtime bindings</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Bind strategies to symbol universes. Enable runtime to start catalog-driven scanning.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="rounded-full bg-emerald-900/40 px-3 py-0.5 text-xs font-semibold text-emerald-400">
            {activeCount} active
          </span>
          <button
            type="button"
            onClick={() => setShowCreate((v) => !v)}
            className="flex items-center gap-1.5 rounded-lg bg-violet-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-violet-500"
          >
            <Plus className="h-3.5 w-3.5" />
            New binding
          </button>
        </div>
      </div>

      {/* Create form */}
      {showCreate && (
        <div className="rounded-xl border border-violet-800/40 bg-violet-950/20 p-4 space-y-3">
          <p className="text-sm font-semibold text-violet-300">New runtime binding</p>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Strategy</label>
              <select
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
                value={form.strategyCatalogId}
                onChange={(e) => setForm((f) => ({ ...f, strategyCatalogId: e.target.value }))}
              >
                <option value="">Select strategy…</option>
                {(strategiesQ.data?.content ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.displayName ?? s.code} [{s.assetClass ?? "EQ"}]
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Universe group</label>
              <select
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
                value={form.universeGroupId}
                onChange={(e) => setForm((f) => ({ ...f, universeGroupId: e.target.value }))}
              >
                <option value="">Select universe…</option>
                {(groupsQ.data?.content ?? []).map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.displayName} [{g.assetClass} / {g.instrumentType}] — {g.symbolCount} symbols
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Max positions</label>
              <input
                type="number"
                min={1}
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
                value={form.maxPositions}
                onChange={(e) => setForm((f) => ({ ...f, maxPositions: Number(e.target.value) }))}
              />
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Scan interval (seconds)</label>
              <input
                type="number"
                min={10}
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
                value={form.scanIntervalSeconds}
                onChange={(e) => setForm((f) => ({ ...f, scanIntervalSeconds: Number(e.target.value) }))}
              />
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Risk profile</label>
              <select
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white"
                value={form.riskProfile}
                onChange={(e) => setForm((f) => ({ ...f, riskProfile: e.target.value }))}
              >
                {RISK_PROFILES.map((r) => <option key={r}>{r}</option>)}
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-neutral-500">Capital limit (optional)</label>
              <input
                type="number"
                min={0}
                placeholder="e.g. 500000"
                className="w-full rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-white placeholder:text-neutral-600"
                value={form.capitalLimit}
                onChange={(e) => setForm((f) => ({ ...f, capitalLimit: e.target.value }))}
              />
            </div>
          </div>
          <label className="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer">
            <input
              type="checkbox"
              checked={form.runtimeEnabled}
              onChange={(e) => setForm((f) => ({ ...f, runtimeEnabled: e.target.checked }))}
              className="accent-violet-500"
            />
            Enable runtime scanning immediately
          </label>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!form.strategyCatalogId || !form.universeGroupId || createMut.isPending}
              onClick={() =>
                createMut.mutate({
                  ...form,
                  capitalLimit: form.capitalLimit ? Number(form.capitalLimit) : undefined,
                })
              }
              className="rounded-lg bg-violet-600 px-4 py-1.5 text-xs font-semibold text-white disabled:opacity-50 hover:bg-violet-500"
            >
              {createMut.isPending ? "Creating…" : "Create binding"}
            </button>
            <button type="button" onClick={() => setShowCreate(false)} className="rounded-lg bg-neutral-800 px-4 py-1.5 text-xs font-semibold text-neutral-300">
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Bindings table */}
      {bindingsQ.isLoading ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => <div key={i} className="h-16 animate-pulse rounded-xl border border-neutral-800 bg-neutral-900/40" />)}
        </div>
      ) : bindingsQ.isError ? (
        <div className="rounded-xl border border-red-900/40 bg-red-950/30 px-4 py-3 text-sm text-red-300">
          Failed. {parseAxiosMessage(bindingsQ.error)}
        </div>
      ) : bindings.length === 0 ? (
        <div className="rounded-xl border border-neutral-800 px-4 py-8 text-center text-sm text-neutral-500">
          No runtime bindings. Create one above to start catalog-driven scanning.
        </div>
      ) : (
        <div className="space-y-2">
          {bindings.map((b) => (
            <BindingRow
              key={b.id}
              binding={b}
              onToggle={() => toggleMut.mutate({ id: b.id, enabled: !b.runtimeEnabled })}
              onDelete={() => {
                if (confirm(`Remove binding ${b.strategyKey} → ${b.groupKey}?`)) deleteMut.mutate(b.id);
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function BindingRow({
  binding: b,
  onToggle,
  onDelete,
}: {
  binding: RuntimeBindingDto;
  onToggle: () => void;
  onDelete: () => void;
}) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-3 rounded-xl border px-4 py-3",
        b.runtimeEnabled
          ? "border-emerald-800/40 bg-emerald-950/10"
          : "border-neutral-800 bg-neutral-900/30",
      )}
    >
      {/* Status indicator */}
      <div className={cn("h-2 w-2 shrink-0 rounded-full", b.runtimeEnabled ? "bg-emerald-400" : "bg-neutral-600")} />

      {/* Strategy info */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-white">
          <Link className="h-3.5 w-3.5 shrink-0 text-violet-400" />
          <span className="truncate">{b.strategyDisplayName ?? b.strategyKey}</span>
          <span className="text-neutral-500">→</span>
          <span className="text-violet-300 truncate">{b.groupDisplayName}</span>
        </div>
        <div className="mt-0.5 flex flex-wrap gap-3 text-[11px] text-neutral-500">
          <span>{b.assetClass ?? "EQ"} / {b.instrumentType}</span>
          <span>{b.symbolCount} symbols</span>
          <span>every {b.scanIntervalSeconds}s</span>
          <span>max {b.maxPositions} pos</span>
          <span className={cn(
            "font-semibold",
            b.riskProfile === "HIGH" ? "text-red-400" : b.riskProfile === "LOW" ? "text-emerald-400" : "text-amber-400",
          )}>
            {b.riskProfile}
          </span>
          {b.capitalLimit && <span>₹{Number(b.capitalLimit).toLocaleString("en-IN")}</span>}
        </div>
      </div>

      {/* Actions */}
      <div className="flex shrink-0 items-center gap-2">
        <button
          type="button"
          onClick={onToggle}
          className={cn(
            "flex items-center gap-1 rounded-lg px-2.5 py-1 text-[11px] font-semibold",
            b.runtimeEnabled
              ? "bg-emerald-700 text-white hover:bg-emerald-600"
              : "bg-neutral-800 text-neutral-400 hover:bg-neutral-700",
          )}
        >
          {b.runtimeEnabled ? (
            <><Activity className="h-3 w-3" /> Live</>
          ) : (
            <><Zap className="h-3 w-3" /> Enable</>
          )}
        </button>
        <button
          type="button"
          onClick={onDelete}
          className="rounded-lg bg-red-950/20 p-1.5 text-red-500 hover:bg-red-900/30"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}
