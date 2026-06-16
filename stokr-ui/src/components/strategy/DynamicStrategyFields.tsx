import type { StrategyParameterField } from "../../types/strategyMetadata";
import { cn } from "../../lib/utils";

const GROUP_ORDER = ["core", "signals", "risk", "filters", "timing", "execution", "_"];

function defaultString(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return "";
}

function defaultNumber(v: unknown): string {
  if (typeof v === "number" && Number.isFinite(v)) return String(v);
  if (typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v))) return v;
  return "";
}

function groupKey(p: StrategyParameterField): string {
  const g = p.group?.trim();
  return g && g.length ? g : "_";
}

/**
 * Renders inputs from server strategy metadata. Field names: `strategyParam.<id>` (collected in submit handler).
 */
export function DynamicStrategyFields({
  parameters,
  disabled,
  className,
}: {
  parameters: StrategyParameterField[];
  disabled?: boolean;
  className?: string;
}) {
  if (!parameters.length) return null;

  const byGroup = new Map<string, StrategyParameterField[]>();
  for (const p of parameters) {
    const g = groupKey(p);
    const arr = byGroup.get(g) ?? [];
    arr.push(p);
    byGroup.set(g, arr);
  }

  function renderField(p: StrategyParameterField) {
    const name = `strategyParam.${p.id}`;
    const base =
      "mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600 disabled:opacity-60";
    const label = (
      <span className="text-neutral-400" title={p.description ?? undefined}>
        {p.label}
        {p.required ? <span className="text-rose-400"> *</span> : null}
      </span>
    );
    if (p.type === "boolean") {
      const checked = Boolean(p.defaultValue === true || p.defaultValue === "true");
      return (
        <label key={p.id} className="block text-sm sm:col-span-2">
          {label}
          <input
            name={name}
            type="checkbox"
            value="true"
            defaultChecked={checked}
            disabled={disabled}
            className="mt-2 h-4 w-4 rounded border-neutral-600 bg-neutral-950"
          />
          {p.description ? <p className="mt-1 text-xs text-neutral-500">{p.description}</p> : null}
        </label>
      );
    }
    if (p.type === "enum" && p.enumValues?.length) {
      return (
        <label key={p.id} className="block text-sm">
          {label}
          <select name={name} className={base} required={p.required} disabled={disabled} defaultValue={defaultString(p.defaultValue)}>
            {p.enumValues.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
          {p.description ? <p className="mt-1 text-xs text-neutral-500">{p.description}</p> : null}
        </label>
      );
    }
    if (p.type === "integer" || p.type === "number") {
      const v = p.validation ?? {};
      const min = typeof v.min === "number" ? v.min : undefined;
      const max = typeof v.max === "number" ? v.max : undefined;
      const stepFromValidation = typeof v.step === "number" ? v.step : undefined;
      const prec = typeof p.precision === "number" ? p.precision : undefined;
      const step =
        stepFromValidation ??
        (p.type === "integer" ? 1 : prec !== undefined ? 1 / 10 ** prec : "any");
      return (
        <label key={p.id} className="block text-sm">
          {label}
          <input
            name={name}
            type="number"
            className={base}
            required={p.required}
            disabled={disabled}
            defaultValue={defaultNumber(p.defaultValue)}
            min={min}
            max={max}
            step={step}
          />
          {p.description ? <p className="mt-1 text-xs text-neutral-500">{p.description}</p> : null}
        </label>
      );
    }
    const maxLen = typeof p.validation?.maxLength === "number" ? p.validation.maxLength : undefined;
    return (
      <label key={p.id} className="block text-sm sm:col-span-2">
        {label}
        <input
          name={name}
          type="text"
          className={base}
          required={p.required}
          disabled={disabled}
          defaultValue={defaultString(p.defaultValue)}
          maxLength={maxLen}
          pattern={typeof p.validation?.pattern === "string" ? p.validation.pattern : undefined}
        />
        {p.description ? <p className="mt-1 text-xs text-neutral-500">{p.description}</p> : null}
      </label>
    );
  }

  return (
    <div className={cn("space-y-6", className)}>
      {GROUP_ORDER.map((g) => {
        const list = byGroup.get(g);
        if (!list?.length) return null;
        const title = g === "_" ? "Parameters" : g.replace(/_/g, " ");
        return (
          <fieldset key={g} className="rounded-xl border border-neutral-800/80 bg-neutral-900/30 p-4">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-neutral-500">{title}</legend>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">{list.map((p) => renderField(p))}</div>
          </fieldset>
        );
      })}
    </div>
  );
}
