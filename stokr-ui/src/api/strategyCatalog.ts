import { api } from "./client";

// ─── Types ───────────────────────────────────────────────────────────────────

export type AdminStrategyDto = {
  id: string;
  code: string;
  displayName: string | null;
  description: string | null;
  enabled: boolean;
  visibleToUsers: boolean;
  riskLevel: string;
  category: string | null;
  strategyType: string | null;
  executionMode: string | null;
  assetClass: string | null;
  segment: string | null;
  defaultTimeframe: string | null;
  defaultExchange: string | null;
  supportsBacktest: boolean;
  supportsLive: boolean;
  supportsPaper: boolean;
  derivativeEnabled: boolean;
  futuresStrategyEnabled: boolean;
  optionStrategyEnabled: boolean;
  templateClassName: string | null;
  generatedClassPath: string | null;
  templateGenerated: boolean;
  catalogVersion: string | null;
  createdAt: string;
  symbols: string[];
};

export type UniverseGroupDto = {
  id: string;
  groupKey: string;
  displayName: string;
  description: string | null;
  universeType: string;
  exchange: string;
  assetClass: string;
  segment: string;
  instrumentType: string;
  autoManaged: boolean;
  enabled: boolean;
  symbolCount: number;
  createdAt: string;
  updatedAt: string;
};

export type UniverseSymbolDto = {
  id: string;
  groupId: string;
  symbol: string;
  tradingSymbol: string | null;
  underlyingSymbol: string | null;
  exchange: string;
  instrumentType: string;
  instrumentToken: number | null;
  lotSize: number | null;
  tickSize: number | null;
  expiry: string | null;
  strike: number | null;
  optionType: string | null;
  enabled: boolean;
  createdAt: string;
};

export type RuntimeBindingDto = {
  id: string;
  strategyCatalogId: string;
  strategyKey: string;
  strategyDisplayName: string | null;
  assetClass: string | null;
  segment: string | null;
  universeGroupId: string;
  groupKey: string;
  groupDisplayName: string;
  instrumentType: string;
  runtimeEnabled: boolean;
  maxPositions: number;
  capitalLimit: number | null;
  riskProfile: string;
  scanIntervalSeconds: number;
  symbolCount: number;
  createdAt: string;
  updatedAt: string;
};

export type PageWrap<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
};

// ─── Strategy catalog ────────────────────────────────────────────────────────

export async function fetchStrategyCatalog(page = 0, size = 50): Promise<PageWrap<AdminStrategyDto>> {
  const res = await api.get(`/api/admin/strategies?page=${page}&size=${size}&sort=strategyKey`);
  return res.data?.data as PageWrap<AdminStrategyDto>;
}

export type StrategyCatalogKeyItem = {
  strategyKey: string;
  displayName: string;
};

/** Flat strategy list for admin dropdowns (Signal Lab, Replay, etc.). */
export async function fetchStrategyCatalogKeys(): Promise<StrategyCatalogKeyItem[]> {
  const page = await fetchStrategyCatalog(0, 100);
  return (page.content ?? [])
    .filter((s) => s.enabled !== false)
    .map((s) => ({
      strategyKey: s.code,
      displayName: s.displayName?.trim() ? s.displayName : s.code,
    }))
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
}

export async function createStrategy(body: {
  strategyKey: string;
  displayName: string;
  description?: string;
  strategyType?: string;
  executionMode?: string;
  assetClass?: string;
  segment?: string;
  defaultTimeframe?: string;
  defaultExchange?: string;
  riskLevel?: string;
  supportsBacktest?: boolean;
  supportsLive?: boolean;
  supportsPaper?: boolean;
  derivativeEnabled?: boolean;
  futuresStrategyEnabled?: boolean;
  optionStrategyEnabled?: boolean;
  templateClassName?: string;
  generateTemplate?: boolean;
  symbols?: string[];
}): Promise<AdminStrategyDto> {
  const res = await api.post("/api/admin/strategies", body);
  return res.data?.data as AdminStrategyDto;
}

export async function generateStrategyTemplate(id: string): Promise<AdminStrategyDto> {
  const res = await api.post(`/api/admin/strategies/${id}/generate-template`);
  return res.data?.data as AdminStrategyDto;
}

export async function patchStrategy(id: string, body: Record<string, unknown>): Promise<AdminStrategyDto> {
  const res = await api.patch(`/api/admin/strategies/${id}`, body);
  return res.data?.data as AdminStrategyDto;
}

export async function searchSymbols(q: string, limit = 30): Promise<string[]> {
  const res = await api.get(`/api/admin/universe-groups/symbols/search?q=${encodeURIComponent(q)}&limit=${limit}`);
  return res.data?.data as string[];
}

export async function deleteStrategy(id: string): Promise<void> {
  await api.delete(`/api/admin/strategies/${id}`);
}

// ─── Universe groups ─────────────────────────────────────────────────────────

export async function fetchUniverseGroups(assetClass?: string, page = 0, size = 50): Promise<PageWrap<UniverseGroupDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size), sort: "groupKey" });
  if (assetClass) params.set("assetClass", assetClass);
  const res = await api.get(`/api/admin/universe-groups?${params}`);
  return res.data?.data as PageWrap<UniverseGroupDto>;
}

export async function createUniverseGroup(body: {
  groupKey: string;
  displayName: string;
  description?: string;
  universeType?: string;
  exchange?: string;
  assetClass?: string;
  segment?: string;
  instrumentType?: string;
  autoManaged?: boolean;
}): Promise<UniverseGroupDto> {
  const res = await api.post("/api/admin/universe-groups", body);
  return res.data?.data as UniverseGroupDto;
}

export async function toggleUniverseGroup(id: string, enabled: boolean): Promise<UniverseGroupDto> {
  const res = await api.patch(`/api/admin/universe-groups/${id}/enabled`, { enabled });
  return res.data?.data as UniverseGroupDto;
}

export async function fetchGroupSymbols(groupId: string): Promise<UniverseSymbolDto[]> {
  const res = await api.get(`/api/admin/universe-groups/${groupId}/symbols`);
  return res.data?.data as UniverseSymbolDto[];
}

export async function bulkImportSymbols(
  groupId: string,
  symbols: { symbol: string; tradingSymbol?: string; exchange?: string; instrumentType?: string; lotSize?: number }[],
  replaceExisting = false,
): Promise<{ imported: number }> {
  const res = await api.post(`/api/admin/universe-groups/${groupId}/symbols/bulk-import`, {
    symbols,
    replaceExisting,
  });
  return res.data?.data as { imported: number };
}

export async function syncUniverseGroup(groupId: string): Promise<{ synced: number }> {
  const res = await api.post(`/api/admin/universe-groups/${groupId}/sync`);
  return res.data?.data as { synced: number };
}

// ─── Runtime bindings ────────────────────────────────────────────────────────

export async function fetchRuntimeBindings(page = 0, size = 50): Promise<PageWrap<RuntimeBindingDto>> {
  const res = await api.get(`/api/admin/runtime-bindings?page=${page}&size=${size}`);
  return res.data?.data as PageWrap<RuntimeBindingDto>;
}

export async function fetchBindingsForStrategy(strategyCatalogId: string): Promise<RuntimeBindingDto[]> {
  const res = await api.get(`/api/admin/runtime-bindings/by-strategy/${strategyCatalogId}`);
  return res.data?.data as RuntimeBindingDto[];
}

export async function createRuntimeBinding(body: {
  strategyCatalogId: string;
  universeGroupId: string;
  runtimeEnabled?: boolean;
  maxPositions?: number;
  capitalLimit?: number;
  riskProfile?: string;
  scanIntervalSeconds?: number;
}): Promise<RuntimeBindingDto> {
  const res = await api.post("/api/admin/runtime-bindings", body);
  return res.data?.data as RuntimeBindingDto;
}

export async function toggleRuntimeBinding(id: string, runtimeEnabled: boolean): Promise<RuntimeBindingDto> {
  const res = await api.patch(`/api/admin/runtime-bindings/${id}/runtime-enabled`, { runtimeEnabled });
  return res.data?.data as RuntimeBindingDto;
}

export async function deleteRuntimeBinding(id: string): Promise<void> {
  await api.delete(`/api/admin/runtime-bindings/${id}`);
}
