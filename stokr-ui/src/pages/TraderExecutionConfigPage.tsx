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
import { PageHeader } from "../components/ds/PageHeader";
import { Badge } from "../components/ds/Badge";
import { toast } from "sonner";

// ── Edit modal ────────────────────────────────────────────────────────────────

function EditModal({
  cfg,
  onClose,
}: {
  cfg: TraderExecutionConfigDto;
  onClose: () => void;
}) {
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="bg-surface border border-border rounded-xl w-full max-w-md p-6 space-y-5">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">
            {cfg.strategyKey}
          </h2>
          <button
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground text-sm"
          >
            ✕
          </button>
        </div>

        {cfg.isGlobalFallback && (
          <p className="text-xs text-amber-500 border border-amber-500/30 bg-amber-500/10 rounded p-2">
            Showing global defaults. Saving will create your personal override.
          </p>
        )}

        {/* Admin-set read-only fields */}
        <div className="rounded-lg bg-muted/30 border border-border p-3 space-y-1 text-sm">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
            Admin-controlled (read-only)
          </p>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Execution mode</span>
            <Badge variant={cfg.executionMode === "LIVE" ? "destructive" : "secondary"}>
              {cfg.executionMode}
            </Badge>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Live enabled</span>
            <span className={cfg.liveEnabled ? "text-green-400" : "text-muted-foreground"}>
              {cfg.liveEnabled ? "Yes" : "No"}
            </span>
          </div>
        </div>

        {/* Trader-editable fields */}
        <div className="space-y-3">
          <ToggleRow
            label="Strategy enabled"
            value={form.enabled}
            onChange={() => toggle("enabled")}
          />
          <ToggleRow
            label="Telegram alerts"
            value={form.telegramEnabled}
            onChange={() => toggle("telegramEnabled")}
          />
          <ToggleRow
            label="Force fixed quantity"
            value={form.forceFixedQty}
            onChange={() => toggle("forceFixedQty")}
          />
          <ToggleRow
            label="Allow pyramiding"
            value={form.allowPyramiding}
            onChange={() => toggle("allowPyramiding")}
          />
          <ToggleRow
            label="Emergency stop"
            value={form.emergencyStopEnabled}
            onChange={() => toggle("emergencyStopEnabled")}
            destructive
          />

          <NumberRow
            label="Fixed qty"
            value={form.fixedQty}
            onChange={(v) => setForm((f) => ({ ...f, fixedQty: v }))}
            min={0.01}
            step={0.01}
          />
          <NumberRow
            label="Max positions"
            value={form.maxPositions}
            onChange={(v) => setForm((f) => ({ ...f, maxPositions: v }))}
            min={0}
            step={1}
          />
          <NumberRow
            label="Daily loss limit"
            value={form.dailyLossLimit ?? 0}
            onChange={(v) => setForm((f) => ({ ...f, dailyLossLimit: v > 0 ? v : null }))}
            min={0}
            placeholder="0 = disabled"
          />
          <NumberRow
            label="Cooldown (minutes)"
            value={form.cooldownMinutes}
            onChange={(v) => setForm((f) => ({ ...f, cooldownMinutes: v }))}
            min={0}
            step={1}
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm rounded-lg border border-border text-muted-foreground hover:text-foreground"
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

function ToggleRow({
  label,
  value,
  onChange,
  destructive,
}: {
  label: string;
  value: boolean;
  onChange: () => void;
  destructive?: boolean;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className={`text-sm ${destructive && value ? "text-red-400" : "text-foreground"}`}>
        {label}
      </span>
      <button
        onClick={onChange}
        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
          value
            ? destructive
              ? "bg-red-500"
              : "bg-primary"
            : "bg-muted"
        }`}
      >
        <span
          className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
            value ? "translate-x-5" : "translate-x-1"
          }`}
        />
      </button>
    </div>
  );
}

function NumberRow({
  label,
  value,
  onChange,
  min,
  step,
  placeholder,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min?: number;
  step?: number;
  placeholder?: string;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-sm text-foreground">{label}</span>
      <input
        type="number"
        value={value}
        min={min}
        step={step ?? 1}
        placeholder={placeholder}
        onChange={(e) => onChange(parseFloat(e.target.value) || 0)}
        className="w-28 rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground text-right"
      />
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
      <PageHeader title="Strategy Settings" subtitle="Manage your personal execution preferences per strategy" />

      <div className="flex-1 overflow-auto p-4 space-y-4">
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="Filter by strategy key…"
            value={searchKey}
            onChange={(e) => setSearchKey(e.target.value)}
            className="w-64 rounded-md border border-border bg-background px-3 py-1.5 text-sm text-foreground"
          />
          <span className="text-xs text-muted-foreground">
            {rows.length} override{rows.length !== 1 ? "s" : ""}
          </span>
        </div>

        {configs.isLoading && (
          <p className="text-sm text-muted-foreground">Loading…</p>
        )}

        {!configs.isLoading && rows.length === 0 && (
          <div className="rounded-xl border border-border bg-surface p-8 text-center space-y-2">
            <p className="text-sm text-muted-foreground">
              No personal overrides yet.
            </p>
            <p className="text-xs text-muted-foreground">
              Click "Edit" on any strategy below, or search for a strategy key to create your first override.
            </p>
            {searchKey && (
              <button
                onClick={() => setEditing(searchKey)}
                className="mt-2 px-4 py-2 text-sm rounded-lg bg-primary text-primary-foreground hover:opacity-90"
              >
                Configure "{searchKey}"
              </button>
            )}
          </div>
        )}

        {rows.length > 0 && (
          <div className="rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 text-muted-foreground text-xs uppercase tracking-wider">
                <tr>
                  <th className="text-left px-4 py-3">Strategy</th>
                  <th className="text-center px-3 py-3">Mode</th>
                  <th className="text-center px-3 py-3">Enabled</th>
                  <th className="text-center px-3 py-3">Live</th>
                  <th className="text-center px-3 py-3">Fixed qty</th>
                  <th className="text-center px-3 py-3">Max pos</th>
                  <th className="text-center px-3 py-3">Daily limit</th>
                  <th className="text-center px-3 py-3">E-Stop</th>
                  <th className="text-right px-4 py-3"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rows.map((c) => (
                  <tr key={c.strategyKey} className="hover:bg-muted/20">
                    <td className="px-4 py-3 font-mono text-xs text-foreground">
                      {c.strategyKey}
                    </td>
                    <td className="px-3 py-3 text-center">
                      <Badge variant={c.executionMode === "LIVE" ? "destructive" : "secondary"}>
                        {c.executionMode}
                      </Badge>
                    </td>
                    <td className="px-3 py-3 text-center">
                      <Dot on={c.enabled} />
                    </td>
                    <td className="px-3 py-3 text-center">
                      <Dot on={c.liveEnabled} />
                    </td>
                    <td className="px-3 py-3 text-center text-muted-foreground">
                      {c.forceFixedQty ? c.fixedQty : "capital"}
                    </td>
                    <td className="px-3 py-3 text-center text-muted-foreground">
                      {c.maxPositions}
                    </td>
                    <td className="px-3 py-3 text-center text-muted-foreground">
                      {c.dailyLossLimit ? `₹${c.dailyLossLimit}` : "—"}
                    </td>
                    <td className="px-3 py-3 text-center">
                      {c.emergencyStopEnabled ? (
                        <span className="text-red-400 font-medium text-xs">ON</span>
                      ) : (
                        <span className="text-muted-foreground text-xs">off</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setEditing(c.strategyKey)}
                        className="text-xs text-primary hover:underline"
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

function Dot({ on }: { on: boolean }) {
  return (
    <span
      className={`inline-block h-2 w-2 rounded-full ${on ? "bg-green-400" : "bg-muted-foreground/40"}`}
    />
  );
}
