import { api } from "./client";

export type TraderExecutionConfigDto = {
  id: string | null;
  strategyKey: string;
  /** true = showing global admin defaults; trader has no personal override yet */
  isGlobalFallback: boolean;
  // Admin-set (read-only for trader)
  executionMode: "PAPER" | "LIVE" | "BOTH";
  liveEnabled: boolean;
  paperEnabled: boolean;
  // Trader-editable
  enabled: boolean;
  telegramEnabled: boolean;
  forceFixedQty: boolean;
  fixedQty: number;
  maxPositions: number;
  dailyLossLimit: number | null;
  cooldownMinutes: number;
  allowPyramiding: boolean;
  emergencyStopEnabled: boolean;
};

export type TraderExecutionConfigPatchRequest = {
  enabled: boolean;
  telegramEnabled: boolean;
  forceFixedQty: boolean;
  fixedQty: number;
  maxPositions: number;
  dailyLossLimit: number | null;
  cooldownMinutes: number;
  allowPyramiding: boolean;
  emergencyStopEnabled: boolean;
};

const BASE = "/api/trader/execution-configs";

export async function fetchMyExecutionConfigs(): Promise<TraderExecutionConfigDto[]> {
  const res = await api.get(BASE);
  return (res.data?.data ?? []) as TraderExecutionConfigDto[];
}

export async function fetchMyExecutionConfig(
  strategyKey: string,
): Promise<TraderExecutionConfigDto> {
  const res = await api.get(`${BASE}/${strategyKey}`);
  return res.data?.data as TraderExecutionConfigDto;
}

export async function patchMyExecutionConfig(
  strategyKey: string,
  body: TraderExecutionConfigPatchRequest,
): Promise<TraderExecutionConfigDto> {
  const res = await api.patch(`${BASE}/${strategyKey}`, body);
  return res.data?.data as TraderExecutionConfigDto;
}
