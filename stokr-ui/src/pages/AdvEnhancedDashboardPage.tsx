import { useEffect } from "react";

export function AdvEnhancedDashboardPage() {
  useEffect(() => {
    // Navigate to the enhanced dashboard HTML file in public folder
    window.location.href = "/adv-enhanced.html";
  }, []);

  return (
    <div className="flex h-full w-full items-center justify-center bg-neutral-50 dark:bg-neutral-950">
      <div className="text-center">
        <div className="inline-flex h-10 w-10 animate-spin rounded-full border-4 border-neutral-300 border-t-blue-600 dark:border-neutral-700 dark:border-t-blue-400" />
        <p className="mt-4 text-sm text-neutral-600 dark:text-neutral-400">Loading Enhanced Dashboard...</p>
      </div>
    </div>
  );
}
