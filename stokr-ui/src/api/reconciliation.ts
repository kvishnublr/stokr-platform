import { api } from "./client";

export type ReconciliationEventDto = {
  id: string;
  userId: string;
  brokerVendor: string;
  symbol: string | null;
  discrepancyType: string;
  brokerQty: number | null;
  internalQty: number | null;
  delta: number | null;
  orderId: string | null;
  status: string;
  notes: string | null;
  resolvedAt: string | null;
  createdAt: string;
};

export async function fetchReconciliationEvents(
  status: "OPEN" | "ALL" = "OPEN",
  limit = 50,
): Promise<ReconciliationEventDto[]> {
  const res = await api.get(`/api/admin/reconciliation/events?status=${status}&limit=${limit}`);
  return (res.data?.data ?? []) as ReconciliationEventDto[];
}

export async function triggerReconciliationRun(): Promise<void> {
  await api.post("/api/admin/reconciliation/trigger");
}
