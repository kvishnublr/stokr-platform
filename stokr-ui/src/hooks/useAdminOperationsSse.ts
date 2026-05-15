import type { QueryClient } from "@tanstack/react-query";
import type { OpsSnapshot } from "../components/admin/cockpit/opsTypes";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";

/**
 * Multiplexed SSE: `tick` carries full operations snapshot payload; `signal`, `execution_failure`, and `ops_realtime`
 * nudge React Query (and optional heartbeat callback).
 */
export async function subscribeAdminOperationsSse(
  queryClient: QueryClient,
  onStatus: (s: "open" | "closed" | "error", detail?: string) => void,
  signal: AbortSignal,
  onOpsRealtime?: () => void,
): Promise<void> {
  const token = localStorage.getItem("accessToken");
  if (!token) {
    onStatus("error", "no access token");
    return;
  }
  const res = await fetch("/api/admin/operations/stream", {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "text/event-stream",
    },
    signal,
  });
  if (!res.ok) {
    onStatus("error", `HTTP ${res.status}`);
    return;
  }
  if (!res.body) {
    onStatus("error", "no response body");
    return;
  }
  onStatus("open");
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const handleFrame = (frame: string) => {
    let eventName = "message";
    const dataLines: string[] = [];
    for (const line of frame.split("\n")) {
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trim());
      }
    }
    if (dataLines.length === 0) {
      return;
    }
    const raw = dataLines.join("\n");
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return;
    }
    if (eventName === "tick" && parsed && typeof parsed === "object") {
      const o = parsed as Record<string, unknown>;
      const payload = o.payload;
      if (payload && typeof payload === "object") {
        queryClient.setQueryData(ADMIN_OPS_SNAPSHOT_KEY, payload as OpsSnapshot);
      }
    }
    if (eventName === "signal" || eventName === "execution_failure") {
      void queryClient.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
    }
    if (eventName === "ops_realtime") {
      onOpsRealtime?.();
      void queryClient.invalidateQueries({ queryKey: ADMIN_OPS_SNAPSHOT_KEY });
    }
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) >= 0) {
        const frame = buffer.slice(0, sep).trim();
        buffer = buffer.slice(sep + 2);
        if (frame.length > 0) {
          handleFrame(frame);
        }
      }
    }
  } finally {
    onStatus("closed");
  }
}
