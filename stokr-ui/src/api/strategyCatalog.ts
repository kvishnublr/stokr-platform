import { api } from "./client";

export type AdminStrategyDto = {
  id: string;
  code: string;
  displayName: string | null;
  description: string | null;
  enabled: boolean;
  visibleToUsers: boolean;
  assetClass: string | null;
  segment: string | null;
  strategyType: string | null;
  defaultTimeframe: string | null;
  defaultExchange: string | null;
  riskLevel: string;
  executionMode: string | null;
  templateGenerated: boolean;
  templateClassName: string | null;
  generatedClassPath: string | null;
  catalogVersion: string | null;
  supportsBacktest: boolean;
  supportsPaper: boolean;
  supportsLive: boolean;
  derivativeEnabled: boolean;
  futuresStrategyEnabled: boolean;
  optionStrategyEnabled: boolean;
  symbols: string[];
  createdAt: string;
};

export type RuntimeBindingDto = {
  id: string;
  strategyCatalogId: string;
  universeGroupId: string;
  strategyKey: string;
  groupKey: string;
  strategyDisplayName?: string;
  groupDisplayName: string;
  runtimeEnabled: boolean;
  maxPositions: number;
  scanIntervalSeconds: number;
  riskProfile: string;
  capitalLimit: number | null;
  assetClass: string;
  instrumentType: string;
  symbolCount: number;
};

export type UniverseGroupDto = {
  id: string;
  groupKey: string;
  displayName: string;
  description?: string;
  assetClass: string;
  segment: string;
  instrumentType: string;
  universeType: string;
  exchange?: string;
  autoManaged: boolean;
  symbolCount: number;
  enabled: boolean;
};

export type UniverseSymbolDto = {
  id: string;
  symbol: string;
};

type StrategyCatalogKey = { strategyKey: string; displayName: string };

type PaginatedContent<T> = { content: T[]; totalElements?: number; totalPages?: number };

export async function searchSymbols(query: string, limit?: number): Promise<string[]> {
  const res = await api.get("/api/admin/universe-groups/symbols/search", { params: { q: query, limit } });
  return (res.data?.data ?? []) as string[];
}

export async function fetchCatalog(): Promise<unknown[]> {
  const res = await api.get("/api/admin/strategies?page=0&size=200");
  return (res.data?.data?.content ?? []) as unknown[];
}

const STRATEGIES_BASE = "/api/admin/strategies";

export async function fetchStrategyCatalog(page: number, size: number): Promise<PaginatedContent<AdminStrategyDto>> {
  const res = await api.get(STRATEGIES_BASE, { params: { page, size } });
  return res.data?.data as PaginatedContent<AdminStrategyDto>;
}

export async function fetchStrategyCatalogKeys(): Promise<StrategyCatalogKey[]> {
  const res = await api.get(STRATEGIES_BASE, { params: { page: 0, size: 200 } });
  const items = (res.data?.data?.content ?? []) as AdminStrategyDto[];
  return items.map((s) => ({ strategyKey: s.code ?? s.id, displayName: s.displayName ?? s.code ?? s.id }));
}

export async function createStrategy(body: Record<string, unknown>): Promise<AdminStrategyDto> {
  const res = await api.post(STRATEGIES_BASE, body);
  return res.data?.data as AdminStrategyDto;
}

export async function patchStrategy(id: string, body: Record<string, unknown>): Promise<AdminStrategyDto> {
  const res = await api.patch(`${STRATEGIES_BASE}/${id}`, body);
  return res.data?.data as AdminStrategyDto;
}

export async function generateStrategyTemplate(id: string): Promise<{ templateClassName: string }> {
  const res = await api.post(`${STRATEGIES_BASE}/${id}/generate-template`);
  return res.data?.data as { templateClassName: string };
}

export async function deleteStrategy(id: string): Promise<void> {
  await api.delete(`${STRATEGIES_BASE}/${id}`);
}

const BINDINGS_BASE = "/api/admin/runtime-bindings";

export async function fetchRuntimeBindings(page: number, size: number): Promise<PaginatedContent<RuntimeBindingDto>> {
  const res = await api.get(BINDINGS_BASE, { params: { page, size } });
  return res.data?.data as PaginatedContent<RuntimeBindingDto>;
}

export async function createRuntimeBinding(body: Record<string, unknown>): Promise<RuntimeBindingDto> {
  const res = await api.post(BINDINGS_BASE, body);
  return res.data?.data as RuntimeBindingDto;
}

export async function toggleRuntimeBinding(id: string, enabled: boolean): Promise<RuntimeBindingDto> {
  const res = await api.patch(`${BINDINGS_BASE}/${id}/runtime-enabled`, { runtimeEnabled: enabled });
  return res.data?.data as RuntimeBindingDto;
}

export async function deleteRuntimeBinding(id: string): Promise<void> {
  await api.delete(`${BINDINGS_BASE}/${id}`);
}

const UNIVERSE_BASE = "/api/admin/universe-groups";

export async function fetchUniverseGroups(
  assetFilter: string | undefined,
  page: number,
  size: number,
): Promise<PaginatedContent<UniverseGroupDto>> {
  const params: Record<string, unknown> = { page, size };
  if (assetFilter) params.assetClass = assetFilter;
  const res = await api.get(UNIVERSE_BASE, { params });
  return res.data?.data as PaginatedContent<UniverseGroupDto>;
}

export async function createUniverseGroup(body: Record<string, unknown>): Promise<UniverseGroupDto> {
  const res = await api.post(UNIVERSE_BASE, body);
  return res.data?.data as UniverseGroupDto;
}

export async function toggleUniverseGroup(id: string, enabled: boolean): Promise<UniverseGroupDto> {
  const res = await api.patch(`${UNIVERSE_BASE}/${id}/enabled`, { enabled });
  return res.data?.data as UniverseGroupDto;
}

export async function syncUniverseGroup(id: string): Promise<{ synced: number }> {
  const res = await api.post(`${UNIVERSE_BASE}/${id}/sync`);
  return res.data?.data as { synced: number };
}

export async function fetchGroupSymbols(groupId: string): Promise<UniverseSymbolDto[]> {
  const res = await api.get(`${UNIVERSE_BASE}/${groupId}/symbols`);
  return (res.data?.data ?? []) as UniverseSymbolDto[];
}

export async function bulkImportSymbols(
  groupId: string,
  symbols: { symbol: string }[],
  replace: boolean,
): Promise<{ imported: number }> {
  const res = await api.post(`${UNIVERSE_BASE}/${groupId}/symbols/bulk-import`, { symbols, replace });
  return res.data?.data as { imported: number };
}
