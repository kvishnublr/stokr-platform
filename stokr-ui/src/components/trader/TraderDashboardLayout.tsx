import { useEffect, useRef, type ReactNode } from "react";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

/** Main + right rail only - workspace chrome lives in `ShellLayout` / `WorkspaceTopNav`. */
export function TraderDashboardLayout({ main, rightRail }: { main: ReactNode; rightRail: ReactNode }) {
  const mainRef = useRef<HTMLElement | null>(null);
  const railRef = useRef<HTMLElement | null>(null);
  const isLight = useUiThemeStore((s) => s.mode === "light");

  useEffect(() => {
    if (mainRef.current) mainRef.current.scrollTop = 0;
    if (railRef.current) railRef.current.scrollTop = 0;
  }, []);

  return (
    <div
      className={cn(
        "flex w-full min-h-0 flex-1 flex-col overflow-hidden",
        "min-h-[320px] lg:h-[calc(100dvh-12rem)]",
        isLight ? "text-neutral-900" : "text-neutral-100",
      )}
    >
      <div className="flex min-h-0 min-w-0 flex-1 gap-5 overflow-hidden lg:gap-6">
        <main ref={mainRef} className={cn("min-h-0 min-w-0 flex-1 overflow-y-auto pr-1 [-ms-overflow-style:none] [scrollbar-width:thin]")}>
          {main}
        </main>
        <aside
          ref={railRef}
          className="hidden min-h-0 w-[min(24rem,32vw)] shrink-0 overflow-y-auto lg:block [-ms-overflow-style:none] [scrollbar-width:thin]"
        >
          {rightRail}
        </aside>
      </div>
    </div>
  );
}
