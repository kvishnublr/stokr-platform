import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { OperationalHistoryStrip } from "../../components/admin/cockpit/AdminCockpitPanels";
import { AdminPageShell, AdminPanel } from "../../components/admin/institutional/AdminDesignSystem";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";
import { useUiThemeStore } from "../../state/uiTheme";

export function AdminAuditPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Governance"
      title="Audit & accountability"
      subtitle={
        <>
          Accountability trail for broker actions, feed pauses, and session tests. User lifecycle changes remain in{" "}
          <Link className="font-mono text-primary underline-offset-2 hover:underline" to="/admin/users">
            User management
          </Link>
          .
        </>
      }
    >
      <AdminPanel
        isLight={isLight}
        title="Operational history"
        subtitle="Recent admin and platform events from the merged ops snapshot"
      >
        <OperationalHistoryStrip snapshot={snapshot.data} />
      </AdminPanel>
    </AdminPageShell>
  );
}
