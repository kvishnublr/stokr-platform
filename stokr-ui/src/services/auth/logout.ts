import { disconnectAllStomp } from "../../lib/realtime/stomp";
import { useNotificationStore } from "../../state/notifications";
import { queryClient } from "../../state/queryClient";
import { useSessionStore } from "../../state/session";

type LogoutArgs = {
  remoteLogout?: () => Promise<unknown>;
};

/**
 * Performs a full local logout teardown to avoid stale auth/RBAC leakage.
 */
export async function performLogout(args?: LogoutArgs): Promise<void> {
  try {
    await args?.remoteLogout?.();
  } catch {
    // Local teardown must still proceed.
  }

  disconnectAllStomp();
  queryClient.clear();
  useNotificationStore.getState().clear();
  useSessionStore.getState().clearSession();

  try {
    sessionStorage.clear();
  } catch {
    /* ignore */
  }
}
