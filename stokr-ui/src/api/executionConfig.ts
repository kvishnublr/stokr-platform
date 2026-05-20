import { api } from "./client";

export type StrategyExecutionConfigDto = {
  id: string;
  createdAt: string;
  updatedAt: string;
  strategyId: string | null;
  strategyKey: string;
  enabled: boolean;
  executionMode: "PAPER" | "LIVE" | "BOTH";
  liveEnabled: boolean;
  paperEnabled: boolean;
  telegramEnabled: boolean;
  allocatedCapital: number | null;
  maxPositions: number;
  maxTradeQuantity: number | null;
  forceFixedQty: boolean;
  fixedQty: number;
  dailyLossLimit: number | null;
  cooldownMinutes: number;
  allowPyramiding: boolean;
  emergencyStopEnabled: boolean;
  autoDisableOnLoss: boolean;
  liveConfirmationRequired: boolean;
};

export type StrategyExecutionConfigRequest = Omit<StrategyExecutionConfigDto, "id" | "createdAt" | "updatedAt">;

const BASE = "/api/admin/strategy-execution-configs";

export async function fetchExecutionConfigs(): Promise<StrategyExecutionConfigDto[]> {
  const res = await api.get(BASE);
  return (res.data?.data ?? []) as StrategyExecutionConfigDto[];
}

export async function fetchExecutionConfig(id: string): Promise<StrategyExecutionConfigDto> {
  const res = await api.get(`${BASE}/${id}`);
  return res.data?.data as StrategyExecutionConfigDto;
}

export async function createExecutionConfig(body: StrategyExecutionConfigRequest): Promise<StrategyExecutionConfigDto> {
  const res = await api.post(BASE, body);
  return res.data?.data as StrategyExecutionConfigDto;
}

export async function updateExecutionConfig(id: string, body: StrategyExecutionConfigRequest): Promise<StrategyExecutionConfigDto> {
  const res = await api.put(`${BASE}/${id}`, body);
  return res.data?.data as StrategyExecutionConfigDto;
}

export async function patchExecutionConfig(id: string, body: Partial<StrategyExecutionConfigRequest>): Promise<StrategyExecutionConfigDto> {
  const res = await api.patch(`${BASE}/${id}`, body);
  return res.data?.data as StrategyExecutionConfigDto;
}

export async function deleteExecutionConfig(id: string): Promise<void> {
  await api.delete(`${BASE}/${id}`);
}
