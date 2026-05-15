import { BackfillOperationsPanel } from "../../components/admin/cockpit/AdminCockpitPanels";

export function AdminBackfillPage() {
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Backfill operations</h1>
        <p className="mt-1 text-xs text-muted-foreground">Historical coverage, gap repair, and aggregate rebuild entry points.</p>
      </div>
      <BackfillOperationsPanel />
    </div>
  );
}
