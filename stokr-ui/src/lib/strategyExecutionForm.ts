import type { StrategyMetadataResponse, StrategyParameterField } from "../types/strategyMetadata";

const RESERVED = new Set(
  ["symbol", "timeframe", "capital", "executionmode", "executionprofile", "feemodel", "slippagemodel", "seed", "range"].map((s) =>
    s.toLowerCase(),
  ),
);

function toNum(raw: FormDataEntryValue | null): number {
  const s = String(raw ?? "").trim();
  if (!s) return Number.NaN;
  return Number(s);
}

function toInt(raw: FormDataEntryValue | null): number {
  return parseInt(String(raw ?? "").trim(), 10);
}

/** Collect `strategyParam.<id>` into a plain object (checkboxes read via `form` query). */
export function collectStrategyParameters(form: HTMLFormElement, fields: StrategyParameterField[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of fields) {
    const name = `strategyParam.${p.id}`;
    if (p.type === "boolean") {
      const cb = form.querySelector<HTMLInputElement>(`input[type="checkbox"][name="${name}"]`);
      out[p.id] = Boolean(cb?.checked);
      continue;
    }
    const fd = new FormData(form);
    const raw = fd.get(name);
    if (p.type === "integer") {
      out[p.id] = toInt(raw);
    } else if (p.type === "number") {
      out[p.id] = toNum(raw);
    } else {
      out[p.id] = raw === null ? "" : String(raw);
    }
  }
  return out;
}

/** Lightweight client-side checks mirroring server rules (no silent coercion). */
export function validateClientExecution(
  meta: StrategyMetadataResponse,
  strategyParameters: Record<string, unknown>,
): string | null {
  for (const k of Object.keys(strategyParameters)) {
    if (RESERVED.has(k.toLowerCase())) {
      return `Reserved strategy parameter key: ${k}`;
    }
  }
  for (const p of meta.parameters) {
    const raw = strategyParameters[p.id];
    if (raw === undefined || raw === null || (typeof raw === "string" && raw === "")) {
      if (p.required) return `Missing: ${p.label} (${p.id})`;
      continue;
    }
    if (p.type === "number" || p.type === "integer") {
      const n = typeof raw === "number" ? raw : Number(raw);
      if (!Number.isFinite(n)) return `Invalid number: ${p.label}`;
      if (p.type === "integer" && Math.floor(n) !== n) return `Must be whole number: ${p.label}`;
      const v = p.validation;
      if (v && typeof v.min === "number" && n < v.min) return `${p.label} below minimum`;
      if (v && typeof v.max === "number" && n > v.max) return `${p.label} above maximum`;
    }
    if (p.type === "enum" && p.enumValues && !p.enumValues.includes(String(raw))) {
      return `Invalid choice for ${p.label}`;
    }
  }
  return null;
}
