import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "../../lib/utils";

export function TraderDashboardLayout({
  sidebar,
  topbar,
  main,
  rightRail,
}: {
  sidebar: ReactNode;
  topbar: ReactNode;
  main: ReactNode;
  rightRail: ReactNode;
}) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const mainRef = useRef<HTMLElement | null>(null);
  const railRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (mainRef.current) mainRef.current.scrollTop = 0;
    if (railRef.current) railRef.current.scrollTop = 0;
  }, []);

  return (
    <div className="h-full w-full overflow-hidden bg-gray-50">
      <div className="flex h-full">
        <aside className="hidden h-full w-52 overflow-y-auto border-r border-gray-200 bg-white xl:block">{sidebar}</aside>
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          <header className="border-b border-gray-200 bg-white">{topbar}</header>
          <div className="flex min-h-0 min-w-0 flex-1 gap-5 overflow-hidden px-6 py-5">
            <main ref={mainRef} className={cn("min-h-0 min-w-0 flex-1 overflow-y-auto pr-1")}>{main}</main>
            <aside ref={railRef} className="hidden min-h-0 w-96 flex-shrink-0 overflow-y-auto lg:block">{rightRail}</aside>
          </div>
        </div>
      </div>

      {/* Mobile drawer mount point (opened via custom event). */}
      <button
        type="button"
        className="hidden"
        onClick={() => setMobileMenuOpen((v) => !v)}
        id="trader-mobile-menu-trigger"
      />
      {mobileMenuOpen ? (
        <div className="fixed inset-0 z-50 xl:hidden">
          <div className="absolute inset-0 bg-black/30" onClick={() => setMobileMenuOpen(false)} aria-hidden />
          <aside className="absolute left-0 top-0 h-full w-72 overflow-y-auto border-r border-gray-200 bg-white">
            {sidebar}
          </aside>
        </div>
      ) : null}
    </div>
  );
}
