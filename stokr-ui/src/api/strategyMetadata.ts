import { api } from "./client";
import type { StrategyMetadataResponse } from "../types/strategyMetadata";

type ApiEnvelope<T> = { data?: T };

/**
 * Loads published parameter schema for a strategy key (DB-driven; Mean Reversion seeded in V14).
 */
export async function fetchStrategyMetadata(strategyKey: string): Promise<StrategyMetadataResponse> {
  const encoded = encodeURIComponent(strategyKey);
  const res = await api.get<ApiEnvelope<StrategyMetadataResponse>>(`/api/strategies/metadata/${encoded}`);
  const payload = res.data?.data;
  if (!payload || typeof payload !== "object") {
    throw new Error("Unexpected strategy metadata response");
  }
  return payload;
}
