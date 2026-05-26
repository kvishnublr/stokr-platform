import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Outlet } from "react-router-dom";
import { AdminGlobalOpsHeader } from "../components/admin/AdminGlobalOpsHeader";
import { subscribeAdminOperationsSse } from "../hooks/useAdminOperationsSse";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../lib/fetchAdminOpsSnapshotMerged";

/**
 * Wraps all `/admin/*` content: shared operations snapshot + SSE, persistent global ops header.
 */
export function AdminConsoleLayout() {
  const queryClient = useQueryClient();
  const [opsStreamLive, setOpsStreamLive] = useState(false);
  const [lastOpsPushAt, setLastOpsPushAt] = useState<string | undefined>(undefined);
  const [streamError, setStreamError] = useState<string | undefined>(undefined);

  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    refetchInterval: opsStreamLive ? false : 8000,
    retry: 2,
    staleTime: 1500,
  });

  useEffect(() => {
    let cancelled = false;
    const ac = new AbortController();
    let backoffMs = 3000;

    const runLoop = async () => {
      while (!cancelled) {
        await subscribeAdminOperationsSse(
          queryClient,
          (status, detail) => {
            if (status === "open") {
              setOpsStreamLive(true);
              setStreamError(undefined);
              backoffMs = 3000;
            }
            if (status === "error") {
              setOpsStreamLive(false);
              setStreamError(detail ?? "SSE error");
            }
            if (status === "closed") {
              setOpsStreamLive(false);
            }
          },
          ac.signal,
          () => setLastOpsPushAt(new Date().toISOString()),
        );
        if (cancelled || ac.signal.aborted) break;
        await new Promise<void>((resolve) => setTimeout(resolve, backoffMs));
        backoffMs = Math.min(Math.floor(backoffMs * 1.5), 30_000);
      }
    };

    void runLoop();
    return () => {
      cancelled = true;
      ac.abort();
      setOpsStreamLive(false);
    };
  }, [queryClient]);

  return (
    <div className="flex min-h-0 w-full flex-1 flex-col">
      <AdminGlobalOpsHeader
        snapshot={snapshot.data}
        isFetching={snapshot.isFetching}
        opsStreamLive={opsStreamLive}
        lastOpsPushAt={lastOpsPushAt}
        streamError={streamError}
      />
      <div className="flex min-h-0 flex-1 flex-col pt-4">
        <Outlet />
      </div>
    </div>
  );
}
