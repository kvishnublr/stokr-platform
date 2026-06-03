import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useEffect, useRef, useState } from "react";
import { AnimatePresence } from "framer-motion";
import { Outlet, useLocation } from "react-router-dom";
import { AnimatedPage } from "../components/ds/AnimatedPage";
import { AdminGlobalOpsHeader } from "../components/admin/AdminGlobalOpsHeader";
import { AdminBreadcrumbs, AdminQuickActions } from "../components/admin/institutional/AdminNavigationChrome";
import { adminBreadcrumbs } from "../admin/navigation";
import { useAdminWorkspaceStore } from "../admin/adminWorkspaceStore";
import { subscribeAdminOperationsSse } from "../hooks/useAdminOperationsSse";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";
import { readAdminOpsSnapshotCache } from "../lib/adminOpsSnapshotCache";
import { fetchAdminOpsSnapshotMerged } from "../lib/fetchAdminOpsSnapshotMerged";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { OperatorConsole } from "../components/admin/institutional/experience/OperatorConsole";
import { SimulationRuntimeBanner } from "../components/admin/SimulationRuntimeBanner";

const LOADING_CAP_MS = 1_500;

/**
 * Wraps all `/admin/*` content: shared operations snapshot + SSE, persistent global ops header,
 * institutional breadcrumbs, and command palette.
 */
export function AdminConsoleLayout() {
  const queryClient = useQueryClient();
  const location = useLocation();
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const trackVisit = useAdminWorkspaceStore((s) => s.trackVisit);
  const [opsStreamLive, setOpsStreamLive] = useState(false);
  const [lastOpsPushAt, setLastOpsPushAt] = useState<string | undefined>(undefined);
  const [streamError, setStreamError] = useState<string | undefined>(undefined);
  const [loadingCapExpired, setLoadingCapExpired] = useState(false);
  const cachedSnapshot = useRef(readAdminOpsSnapshotCache());

  useEffect(() => {
    void queryClient.prefetchQuery({
      queryKey: ADMIN_OPS_SNAPSHOT_KEY,
      queryFn: fetchAdminOpsSnapshotMerged,
      staleTime: 1500,
    });
  }, [queryClient]);

  useEffect(() => {
    const t = window.setTimeout(() => setLoadingCapExpired(true), LOADING_CAP_MS);
    return () => window.clearTimeout(t);
  }, []);

  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    initialData: cachedSnapshot.current,
    initialDataUpdatedAt: cachedSnapshot.current ? Date.now() - 1000 : undefined,
    refetchInterval: (query) => {
      if (!query.state.data) return 4000;
      return opsStreamLive ? false : 8000;
    },
    retry: (failureCount, error) => {
      if (failureCount >= 3) return false;
      const code = (error as { code?: string })?.code;
      return code === "ECONNABORTED" || code === "ERR_NETWORK" || failureCount < 2;
    },
    retryDelay: (attempt) => Math.min(1500 * 2 ** attempt, 12_000),
    staleTime: 1500,
    placeholderData: (prev) => prev ?? readAdminOpsSnapshotCache(),
    gcTime: 30 * 60 * 1000,
    refetchOnMount: "always",
  });

  const snapshotInitialLoad =
    !snapshot.data && snapshot.isFetching && !snapshot.isError && !loadingCapExpired;

  const health = useQuery({
    queryKey: ["admin-health"],
    queryFn: async () => (await api.get("/api/admin/health", { timeout: 8000 })).data?.data as Record<string, unknown>,
    refetchInterval: 15_000,
    retry: 2,
  });

  useEffect(() => {
    trackVisit(location.pathname);
  }, [location.pathname, trackVisit]);

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

  const crumbs = adminBreadcrumbs(location.pathname);

  return (
    <div className="flex min-h-0 w-full flex-1 flex-col">
      <AdminGlobalOpsHeader
        snapshot={snapshot.data}
        isFetching={snapshot.isFetching}
        snapshotLoading={snapshotInitialLoad}
        opsStreamLive={opsStreamLive}
        lastOpsPushAt={lastOpsPushAt}
        streamError={
          streamError ??
          (snapshot.isError
            ? snapshot.error instanceof Error
              ? snapshot.error.message
              : "snapshot API failed — retrying with backoff"
            : undefined)
        }
      />
      <SimulationRuntimeBanner isLight={isLight} />
      <div
        className={cn(
          "mb-4 flex flex-wrap items-center justify-between gap-3 border-b pb-3",
          isLight ? "border-neutral-200" : "border-neutral-900/80",
        )}
      >
        <AdminBreadcrumbs crumbs={crumbs} />
        <AdminQuickActions isLight={isLight} killActive={Boolean(health.data?.killSwitch)} />
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        <AnimatePresence mode="wait">
          <AnimatedPage pageKey={location.pathname}>
            <Outlet />
          </AnimatedPage>
        </AnimatePresence>
      </div>
      <OperatorConsole isLight={isLight} />
    </div>
  );
}
