import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  fetchMyExecutionConfig,
  fetchMyExecutionConfigs,
  patchMyExecutionConfig,
} from "../api/traderExecutionConfig";
import type {
  TraderExecutionConfigDto,
  TraderExecutionConfigPatchRequest,
} from "../api/traderExecutionConfig";
import { toast } from "sonner";

// ── Inline helpers ────────────────────────────────────────────────────────────

function ModeBadge({ mode }: { mode: string }) {
  const color =
    mode === "LIVE"
      ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border-emerald-500/30"
      : mode === "BOTH"
        ? "bg-sky-500/15 text-sky-600 dark:text-sky-400 border-sky-500/30"
        : "bg-neutral-500/15 text-neutral-600 dark:text-neutral-400 border-neutral-500/30";
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border ${color}`}>
      {mode}
    </span>
  );
}

function Dot({ on }: { on: boolean }) {
  return (
    <span
      className={`inline-block h-2 w-2 rounded-full ${on ? "bg-green-500" : "bg-muted-foreground/30"}`}
    />
  );
}

function Toggle({ value, onChange, destructive }: { value: boolean; onChange: () => void; destructive?: boolean }) {
  return (
    <button
      onClick={onChange}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
        value ? (destructive ? "bg-red-500" : "bg-primary") : "bg-border"
      }`}
    >
      <span
        className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
          value ? "translate-x-5" : "translate-x-1"
        }`}
      />
    </button>
  );
}

// ── Edit modal ────────────────────────────────────────────────────────────────

function EditModal({ cfg, onClose }: { cfg: TraderExecutionConfigDto; onClose: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<TraderExecutionConfigPatchRequest>({
    enabled: cfg.enabled,
    telegramEnabled: cfg.telegramEnabled,
    forceFixedQty: cfg.forceFixedQty,
    fixedQty: cfg.fixedQty,
    maxPositions: cfg.maxPositions,
    dailyLossLimit: cfg.dailyLossLimit,
    cooldownMinutes: cfg.cooldownMinutes,
    allowPyramiding: cfg.allowPyramiding,
    emergencyStopEnabled: cfg.emergencyStopEnabled,
  });

  const mut = useMutation({
    mutationFn: () => patchMyExecutionConfig(cfg.strategyKey, form),
    onSuccess: () => {
      toast.success(`Settings saved for ${cfg.strategyKey}`);
      void qc.invalidateQueries({ queryKey: ["trader-exec-configs"] });
      onClose();
    },
    onError: () => toast.error("Failed to save settings"),
  });

  const toggle = (key: keyof TraderExecutionConfigPatchRequest) =>
    setForm((f) => ({ ...f, [key]: !f[key] }));

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-card border border-border rounded-xl w-full max-w-md p-6 space-y-5 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-foreground font-mono">{cfg.strategyKey}</h2>
          <button onClick={onClose} className="text-muted-foreground hover:text-foreground text-sm">✕</button>
        </div>

        {cfg.isGlobalFallback && (
          <p className="text-xs text-amber-600 dark:text-amber-400 border border-amber-500/30 bg-amber-500/10 rounded p-2">
            Showing global defaults. Saving will create your personal override.
          </p>
        )}

        {/* Admin read-only */}
        <div className="rounded-lg bg-muted/40 border border-border p-3 space-y-2 text-sm">
          <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">Admin-controlled (read only)</p>
          <div className="flex justify-between items-center">
            <span className="text-muted-foreground text-xs">Execution mode</span>
            <ModeBadge mode={cfg.executionMode} />
          </div>
          <div className="flex justify-between items-center">
            <span className="text-muted-foreground text-xs">Live enabled</span>
            <span className={`text-xs font-medium ${cfg.liveEnabled ? "text-green-600 dark:text-green-400" : "text-muted-foreground"}`}>
              {cfg.liveEnabled ? "Yes" : "No"}
            </span>
          </div>
        </div>

        {/* Trader-editable toggles */}
        <div className="space-y-3">
          {(
            [
              ["Strategy enabled", "enabled", false],
              ["Telegram alerts", "telegramEnabled", false],
              ["Force fixed quantity", "forceFixedQty", false],
              ["Allow pyramiding", "allowPyramiding", false],
              ["Emergency stop", "emergencyStopEnabled", true],
            ] as [string, keyof TraderExecutionConfigPatchRequest, boolean][]
          ).map(([label, key, dest]) => (
            <div key={key} className="flex items-center justify-between">
              <span className={`text-sm ${dest && form[key] ? "text-red-500 dark:text-red-400" : "text-foreground"}`}>{label}</span>
              <Toggle value={form[key] as boolean} onChange={() => toggle(key)} destructive={dest} />
            </div>
          ))}

          {(
            [
              ["Fixed qty", "fixedQty", 0.01, 0.01],
              ["Max positions", "maxPositions", 0, 1],
              ["Cooldown (minutes)", "cooldownMinutes", 0, 1],
            ] as [string, keyof TraderExecutionConfigPatchRequest, number, number][]
          ).map(([label, key, min, step]) => (
            <div key={key} className="flex items-center justify-between gap-4">
              <span className="text-sm text-foreground">{label}</span>
              <input
                type="number"
                value={form[key] as number}
                min={min}
                step={step}
                onChange={(e) => setForm((f) => ({ ...f, [key]: parseFloat(e.target.value) || 0 }))}
                className="w-28 rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground text-right focus:outline-none focus:ring-1 focus:ring-primary"
              />
            </div>
          ))}

          <div className="flex items-center justify-between gap-4">
            <span className="text-sm text-foreground">Daily loss limit</span>
            <input
              type="number"
              value={form.dailyLossLimit ?? 0}
              min={0}
              placeholder="0 = disabled"
              onChange={(e) => {
                const v = parseFloat(e.target.value);
                setForm((f) => ({ ...f, dailyLossLimit: v > 0 ? v : null }));
              }}
              className="w-28 rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground text-right focus:outline-none focus:ring-1 focus:ring-primary"
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 pt-2 border-t border-border">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm rounded-lg border border-border text-muted-foreground hover:text-foreground hover:bg-muted/40"
          >
            Cancel
          </button>
          <button
            onClick={() => mut.mutate()}
            disabled={mut.isPending}
            className="px-4 py-2 text-sm rounded-lg bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
          >
            {mut.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export function TraderExecutionConfigPage() {
  const [editing, setEditing] = useState<string | null>(null);
  const [searchKey, setSearchKey] = useState("");

  const configs = useQuery({
    queryKey: ["trader-exec-configs"],
    queryFn: fetchMyExecutionConfigs,
    refetchInterval: 30_000,
  });

  const editQuery = useQuery({
    queryKey: ["trader-exec-config", editing],
    queryFn: () => fetchMyExecutionConfig(editing!),
    enabled: editing !== null,
  });

  const rows = (configs.data ?? []).filter((c) =>
    c.strategyKey.toLowerCase().includes(searchKey.toLowerCase()),
  );

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-6 py-5 border-b border-border">
        <h1 className="text-xl font-semibold text-foreground">Strategy Settings</h1>
        <p className="text-sm text-muted-foreground mt-0.5">
          Manage your personal execution preferences per strategy
        </p>
      </div>

      <div className="flex-1 overflow-auto p-6 space-y-4">
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="Search strategy…"
            value={searchKey}
            onChange={(e) => setSearchKey(e.target.value)}
            className="w-64 rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          />
          <span className="text-xs text-muted-foreground">
            {rows.length} strateg{rows.length !== 1 ? "ies" : "y"}
            {configs.data && rows.length < configs.data.length ? ` (filtered from ${configs.data.length})` : ""}
          </span>
        </div>

        {configs.isLoading && (
          <p className="text-sm text-muted-foreground">Loading…</p>
        )}

        {!configs.isLoading && rows.length === 0 && (
          <div className="rounded-xl border border-border bg-muted/20 p-10 text-center">
            <p className="text-sm text-muted-foreground">
              {searchKey ? `No strategies matching "${searchKey}".` : "No strategies configured yet. Ask your admin to add strategy configs."}
            </p>
          </div>
        )}

        {rows.length > 0 && (
          <div className="rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-muted-foreground text-xs uppercase tracking-wider border-b border-border">
                <tr>
                  <th className="text-left px-4 py-3">Strategy</th>
                  <th className="text-center px-3 py-3">Mode</th>
                  <th className="text-center px-3 py-3">Active</th>
                  <th className="text-center px-3 py-3">Live</th>
                  <th className="text-center px-3 py-3">Qty</th>
                  <th className="text-center px-3 py-3">Max pos</th>
                  <th className="text-center px-3 py-3">Daily limit</th>
                  <th className="text-center px-3 py-3">E-Stop</th>
                  <th className="text-right px-4 py-3"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rows.map((c) => (
                  <tr key={c.strategyKey} className="hover:bg-muted/30 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-foreground">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold">{c.strategyKey}</span>
                        {c.isGlobalFallback && (
                          <span className="text-[10px] text-muted-foreground border border-border rounded px-1 py-0.5">default</span>
                        )}
                      </div>
                    </td>
                    <td className="px-3 py-3 text-center">
                      <ModeBadge mode={c.executionMode} />
                    </td>
                    <td className="px-3 py-3 text-center"><Dot on={c.enabled} /></td>
                    <td className="px-3 py-3 text-center"><Dot on={c.liveEnabled} /></td>
                    <td className="px-3 py-3 text-center text-foreground text-xs font-mono">
                      {c.forceFixedQty ? c.fixedQty : "capital"}
                    </td>
                    <td className="px-3 py-3 text-center text-foreground text-xs font-mono">{c.maxPositions}</td>
                    <td className="px-3 py-3 text-center text-foreground text-xs font-mono">
                      {c.dailyLossLimit ? `₹${c.dailyLossLimit}` : "—"}
                    </td>
                    <td className="px-3 py-3 text-center">
                      {c.emergencyStopEnabled ? (
                        <span className="text-red-600 dark:text-red-400 font-semibold text-xs">ON</span>
                      ) : (
                        <span className="text-muted-foreground text-xs">off</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setEditing(c.strategyKey)}
                        className="text-xs text-primary hover:underline font-medium"
                      >
                        Edit
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing !== null && editQuery.data && (
        <EditModal cfg={editQuery.data} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}
