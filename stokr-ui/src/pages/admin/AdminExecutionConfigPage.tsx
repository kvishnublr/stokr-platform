import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Plus, Pencil, Trash2, RefreshCw } from "lucide-react";
import {
  createExecutionConfig,
  deleteExecutionConfig,
  fetchExecutionConfigs,
  patchExecutionConfig,
  updateExecutionConfig,
  type StrategyExecutionConfigDto,
  type StrategyExecutionConfigRequest,
} from "../../api/executionConfig";
import { fetchStrategyCatalog, type AdminStrategyDto } from "../../api/strategyCatalog";
import { parseAxiosMessage } from "../../api/client";
import { fmtDateTime } from "../../lib/dateUtils";

const QK = ["admin-execution-configs"] as const;

const EMPTY_FORM: StrategyExecutionConfigRequest = {
  strategyId: null,
  strategyKey: "",
  enabled: true,
  executionMode: "BOTH",
  liveEnabled: true,
  paperEnabled: true,
  telegramEnabled: true,
  allocatedCapital: null,
  maxPositions: 2,
  maxTradeQuantity: null,
  forceFixedQty: true,
  fixedQty: 1,
  dailyLossLimit: null,
  cooldownMinutes: 0,
  allowPyramiding: false,
  emergencyStopEnabled: false,
  autoDisableOnLoss: false,
  liveConfirmationRequired: false,
};

function modeBadgeClass(mode: StrategyExecutionConfigDto["executionMode"]): string {
  if (mode === "LIVE") return "bg-emerald-100 text-emerald-900 ring-1 ring-emerald-200";
  if (mode === "BOTH") return "bg-sky-100 text-sky-900 ring-1 ring-sky-200";
  return "bg-neutral-100 text-neutral-800 ring-1 ring-neutral-200";
}

function modeLabel(mode: StrategyExecutionConfigDto["executionMode"]): string {
  if (mode === "BOTH") return "LIVE + PAPER";
  return mode;
}

function executionModeSideEffects(mode: "PAPER" | "LIVE" | "BOTH"): Pick<
  StrategyExecutionConfigRequest,
  "liveEnabled" | "paperEnabled"
> {
  if (mode === "BOTH") return { liveEnabled: true, paperEnabled: true };
  if (mode === "LIVE") return { liveEnabled: true, paperEnabled: false };
  return { liveEnabled: false, paperEnabled: true };
}

function configToForm(c: StrategyExecutionConfigDto): StrategyExecutionConfigRequest {
  return {
    strategyId: c.strategyId,
    strategyKey: c.strategyKey,
    enabled: c.enabled,
    executionMode: c.executionMode,
    liveEnabled: c.liveEnabled,
    paperEnabled: c.paperEnabled,
    telegramEnabled: c.telegramEnabled,
    allocatedCapital: c.allocatedCapital,
    maxPositions: c.maxPositions,
    maxTradeQuantity: c.maxTradeQuantity,
    forceFixedQty: c.forceFixedQty,
    fixedQty: c.fixedQty,
    dailyLossLimit: c.dailyLossLimit,
    cooldownMinutes: c.cooldownMinutes,
    allowPyramiding: c.allowPyramiding,
    emergencyStopEnabled: c.emergencyStopEnabled,
    autoDisableOnLoss: c.autoDisableOnLoss,
    liveConfirmationRequired: c.liveConfirmationRequired,
  };
}

export function AdminExecutionConfigPage() {
  const qc = useQueryClient();
  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<StrategyExecutionConfigDto | null>(null);
  const [form, setForm] = useState<StrategyExecutionConfigRequest>(EMPTY_FORM);

  const configs = useQuery({ queryKey: QK, queryFn: fetchExecutionConfigs, staleTime: 15_000, refetchInterval: 30_000 });
  const catalog = useQuery({
    queryKey: ["admin-strategy-catalog-all"],
    queryFn: () => fetchStrategyCatalog(0, 200),
    staleTime: 60_000,
  });
  const catalogStrategies: AdminStrategyDto[] = catalog.data?.content ?? [];

  const save = useMutation({
    mutationFn: (f: StrategyExecutionConfigRequest) =>
      editTarget ? updateExecutionConfig(editTarget.id, f) : createExecutionConfig(f),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK });
      toast.success(editTarget ? "Config updated" : "Config created");
      closeModal();
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const toggleEmergencyStop = useMutation({
    mutationFn: ({ id, cfg, stop }: { id: string; cfg: StrategyExecutionConfigDto; stop: boolean }) =>
      patchExecutionConfig(id, { ...configToForm(cfg), emergencyStopEnabled: stop }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK });
      toast.success("Emergency stop updated");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const toggleLive = useMutation({
    mutationFn: ({ id, cfg, live }: { id: string; cfg: StrategyExecutionConfigDto; live: boolean }) =>
      patchExecutionConfig(id, { ...configToForm(cfg), liveEnabled: live }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK });
      toast.success("Live mode updated");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const remove = useMutation({
    mutationFn: deleteExecutionConfig,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK });
      toast.success("Config deleted");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  function openCreate() {
    setEditTarget(null);
    setForm(EMPTY_FORM);
    setShowModal(true);
  }

  function openEdit(c: StrategyExecutionConfigDto) {
    setEditTarget(c);
    setForm(configToForm(c));
    setShowModal(true);
  }

  function closeModal() {
    setShowModal(false);
    setEditTarget(null);
  }

  function setField<K extends keyof StrategyExecutionConfigRequest>(
    k: K, v: StrategyExecutionConfigRequest[K]
  ) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  const rows = configs.data ?? [];

  return (
    <div className="space-y-4 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Strategy Execution Config</h1>
          <p className="mt-1 text-xs text-muted-foreground">
            Per-strategy position sizing, capital allocation, risk gates, and live/paper routing.
            Default for new configs: <span className="font-mono text-foreground">BOTH (LIVE + PAPER)</span>, fixed qty=1.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => void configs.refetch()}
            disabled={configs.isFetching}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-2.5 py-1.5 text-xs font-semibold hover:bg-background/80 disabled:opacity-40"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${configs.isFetching ? "animate-spin" : ""}`} />
          </button>
          <button
            type="button"
            onClick={openCreate}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground hover:bg-primary/90"
          >
            <Plus className="h-3.5 w-3.5" />
            New config
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto rounded-xl border border-border bg-card">
        <table className="w-full border-collapse text-left text-xs">
          <thead className="bg-card text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
            <tr>
              {["Strategy key", "Mode", "Sizing", "Max pos", "Capital", "Daily limit", "Live", "Emergency", "Updated", "Actions"].map((h) => (
                <th key={h} className="border-b border-border px-3 py-2.5">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={10} className="px-3 py-6 text-center text-muted-foreground">
                  No execution configs. Create one to override defaults.
                </td>
              </tr>
            )}
            {rows.map((c) => (
              <tr key={c.id} className="border-b border-border/60 hover:bg-background/30">
                <td className="px-3 py-2 font-mono font-semibold text-foreground">{c.strategyKey}</td>
                <td className="px-3 py-2">
                  <span className={`inline-block rounded px-1.5 py-0.5 text-[10px] font-semibold ${modeBadgeClass(c.executionMode)}`}>
                    {modeLabel(c.executionMode)}
                  </span>
                </td>
                <td className="px-3 py-2 font-mono">
                  {c.forceFixedQty ? `FIXED ${c.fixedQty}` : "CAPITAL"}
                </td>
                <td className="px-3 py-2 font-mono">{c.maxPositions}</td>
                <td className="px-3 py-2 font-mono text-muted-foreground">
                  {c.allocatedCapital != null ? `₹${Number(c.allocatedCapital).toLocaleString("en-IN")}` : "-"}
                </td>
                <td className="px-3 py-2 font-mono text-muted-foreground">
                  {c.dailyLossLimit != null ? `₹${c.dailyLossLimit}` : "-"}
                </td>
                <td className="px-3 py-2">
                  <button
                    type="button"
                    onClick={() => toggleLive.mutate({ id: c.id, cfg: c, live: !c.liveEnabled })}
                    className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ring-1 ${
                      c.liveEnabled
                        ? "bg-emerald-100 text-emerald-900 ring-emerald-200 hover:bg-emerald-200/80"
                        : "bg-neutral-100 text-neutral-700 ring-neutral-200 hover:bg-neutral-200/80"
                    }`}
                    title="Toggle live"
                  >
                    {c.liveEnabled ? "LIVE ON" : "LIVE OFF"}
                  </button>
                </td>
                <td className="px-3 py-2">
                  <button
                    type="button"
                    onClick={() => toggleEmergencyStop.mutate({ id: c.id, cfg: c, stop: !c.emergencyStopEnabled })}
                    className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ring-1 ${
                      c.emergencyStopEnabled
                        ? "bg-red-100 text-red-900 ring-red-200 hover:bg-red-200/80"
                        : "bg-neutral-100 text-neutral-700 ring-neutral-200 hover:bg-neutral-200/80"
                    }`}
                    title="Toggle emergency stop"
                  >
                    {c.emergencyStopEnabled ? "STOPPED" : "RUNNING"}
                  </button>
                </td>
                <td className="px-3 py-2 font-mono text-[10px] text-muted-foreground">
                  {fmtDateTime(c.updatedAt)}
                </td>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => openEdit(c)}
                      className="rounded p-1 text-muted-foreground hover:bg-background hover:text-foreground"
                      title="Edit"
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => { if (confirm(`Delete config for ${c.strategyKey}?`)) remove.mutate(c.id); }}
                      className="rounded p-1 text-muted-foreground hover:bg-background hover:text-red-400"
                      title="Delete"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Production defaults info */}
      <div className="rounded-lg border border-border bg-background/40 p-3 text-xs text-muted-foreground">
        <span className="font-semibold text-foreground">Recommended default:</span>{" "}
        <span className="font-mono text-foreground">BOTH (LIVE + PAPER)</span> for equity strategies —
        enables paper/live drift pairs in Safety Diagnostics. Currency strategies stay PAPER-only.
        New configs use fixed qty=1 unless overridden here.
      </div>

      {/* Modal */}
      {showModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-4 backdrop-blur-sm bg-black/60"
          onClick={(e) => { if (e.target === e.currentTarget) closeModal(); }}
        >
          <div
            className="my-auto w-full max-w-2xl rounded-2xl border border-border bg-card p-5 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-base font-semibold text-foreground">
              {editTarget ? `Edit: ${editTarget.strategyKey}` : "New Execution Config"}
            </h2>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div className="sm:col-span-2">
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Strategy *</label>
                {editTarget ? (
                  <div className="mt-1 w-full rounded-md border border-border bg-background/50 px-2.5 py-1.5 text-xs font-mono text-muted-foreground">
                    {form.strategyKey}
                  </div>
                ) : (
                  <select
                    value={form.strategyKey}
                    onChange={(e) => {
                      const key = e.target.value;
                      const def = catalogStrategies.find((s) => s.code === key);
                      setField("strategyKey", key);
                      if (def?.id) setField("strategyId", def.id);
                    }}
                    className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                  >
                    <option value="">— Select strategy —</option>
                    {catalogStrategies.map((s) => (
                      <option key={s.id} value={s.code}>
                        {s.code}{s.displayName ? ` — ${s.displayName}` : ""}
                      </option>
                    ))}
                  </select>
                )}
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Execution mode</label>
                <select
                  value={form.executionMode}
                  onChange={(e) => {
                    const mode = e.target.value as "PAPER" | "LIVE" | "BOTH";
                    setForm((f) => ({ ...f, executionMode: mode, ...executionModeSideEffects(mode) }));
                  }}
                  className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs text-foreground"
                >
                  <option value="PAPER">PAPER only</option>
                  <option value="LIVE">LIVE only</option>
                  <option value="BOTH">BOTH — LIVE + PAPER (recommended default)</option>
                </select>
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Max positions</label>
                <input
                  type="number" min={1} max={100}
                  value={form.maxPositions}
                  onChange={(e) => setField("maxPositions", Number(e.target.value))}
                  className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground"
                />
                <p className="mt-0.5 text-[9px] text-muted-foreground">Max concurrent open positions (default 2)</p>
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Sizing mode</label>
                <div className="mt-1.5 flex items-center gap-3">
                  <label className="flex items-center gap-1.5 text-xs cursor-pointer">
                    <input type="radio" checked={form.forceFixedQty} onChange={() => setField("forceFixedQty", true)} />
                    Fixed qty
                  </label>
                  <label className="flex items-center gap-1.5 text-xs cursor-pointer">
                    <input type="radio" checked={!form.forceFixedQty} onChange={() => setField("forceFixedQty", false)} />
                    Capital-based
                  </label>
                </div>
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                  {form.forceFixedQty ? "Fixed qty (default 1)" : "Allocated capital (₹)"}
                </label>
                {form.forceFixedQty ? (
                  <input
                    type="number" min={1}
                    value={form.fixedQty}
                    onChange={(e) => setField("fixedQty", Number(e.target.value))}
                    className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground"
                  />
                ) : (
                  <input
                    type="number" min={0}
                    value={form.allocatedCapital ?? ""}
                    onChange={(e) => setField("allocatedCapital", e.target.value ? Number(e.target.value) : null)}
                    placeholder="e.g. 100000"
                    className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground"
                  />
                )}
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Daily loss limit (₹)</label>
                <input
                  type="number" min={0}
                  value={form.dailyLossLimit ?? ""}
                  onChange={(e) => setField("dailyLossLimit", e.target.value ? Number(e.target.value) : null)}
                  placeholder="null = disabled"
                  className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground"
                />
              </div>

              <div>
                <label className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Cooldown (minutes)</label>
                <input
                  type="number" min={0}
                  value={form.cooldownMinutes}
                  onChange={(e) => setField("cooldownMinutes", Number(e.target.value))}
                  className="mt-1 w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs font-mono text-foreground"
                />
                <p className="mt-0.5 text-[9px] text-muted-foreground">Min minutes between entries (0 = off)</p>
              </div>

              {/* Toggle flags */}
              <div className="sm:col-span-2">
                <div className="grid gap-2 sm:grid-cols-3">
                  {[
                    { label: "Live enabled", key: "liveEnabled" as const },
                    { label: "Paper enabled", key: "paperEnabled" as const },
                    { label: "Telegram alerts", key: "telegramEnabled" as const },
                    { label: "Allow pyramiding", key: "allowPyramiding" as const },
                    { label: "Auto-disable on loss", key: "autoDisableOnLoss" as const },
                    { label: "Emergency stop", key: "emergencyStopEnabled" as const },
                  ].map(({ label, key }) => (
                    <label key={key} className="flex cursor-pointer items-center gap-2 rounded-md border border-border bg-background/40 px-2.5 py-2 text-xs hover:bg-background/60">
                      <input
                        type="checkbox"
                        checked={form[key] as boolean}
                        onChange={(e) => setField(key, e.target.checked)}
                        className="accent-primary"
                      />
                      {label}
                    </label>
                  ))}
                </div>
              </div>
            </div>

            {(form.liveEnabled || form.executionMode === "BOTH") && (
              <div className="mt-3 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-950">
                {form.executionMode === "BOTH"
                  ? "BOTH mode runs paper and live legs together for drift analytics — live broker orders still require valid Zerodha OAuth and risk gates."
                  : "Live mode enabled — all risk rules must pass before orders are routed to the broker. Ensure Zerodha token is valid before market open."}
              </div>
            )}

            <div className="mt-4 flex justify-end gap-2 border-t border-border pt-4">
              <button
                type="button"
                onClick={closeModal}
                className="rounded-lg px-4 py-2 text-sm text-muted-foreground hover:bg-background"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={save.isPending || !form.strategyKey.trim() || (!editTarget && !form.strategyKey)}
                onClick={() => save.mutate(form)}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                {save.isPending ? "Saving..." : editTarget ? "Update" : "Create"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
