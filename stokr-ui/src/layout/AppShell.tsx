import type { ReactNode } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useState } from "react";
import { Menu } from "lucide-react";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

export function AppShell({
  sidebar,
  topNav,
  children,
  banners,
}: {
  sidebar?: ReactNode;
  topNav: ReactNode;
  children: ReactNode;
  banners?: ReactNode;
}) {
  const [mobileNav, setMobileNav] = useState(false);
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <div className={cn("relative h-full w-full overflow-hidden", isLight ? "bg-neutral-50" : "bg-neutral-950")}>
      {!isLight ? (
        <div className="pointer-events-none absolute inset-x-0 top-0 h-[420px] bg-[radial-gradient(ellipse_at_top,_rgba(59,130,246,0.11),transparent_58%),radial-gradient(ellipse_at_20%_0%,rgba(168,85,247,0.08),transparent_45%)]" />
      ) : (
        <div className="pointer-events-none absolute inset-x-0 top-0 h-[320px] bg-[radial-gradient(ellipse_at_50%_-20%,rgba(59,130,246,0.08),transparent_55%),radial-gradient(ellipse_at_10%_0%,rgba(99,102,241,0.05),transparent_40%)]" />
      )}

      {/* Mobile rails */}
      <div
        className={cn(
          "sticky top-0 z-30 flex items-center gap-2 px-3 py-2 backdrop-blur-lg lg:hidden",
          isLight
            ? "border-b border-neutral-200/90 bg-white/95"
            : "border-b border-neutral-900/80 bg-neutral-950/95",
        )}
      >
        <button
          type="button"
          aria-label="Open navigation"
          onClick={() => setMobileNav(true)}
          className={cn(
            "rounded-lg border p-2",
            isLight
              ? "border-neutral-200 text-neutral-700 hover:bg-neutral-100"
              : "border-neutral-800 text-neutral-200 hover:bg-neutral-900",
          )}
        >
          <Menu className="h-5 w-5" />
        </button>
        <span
          className={cn("text-xs font-semibold tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}
        >
          Stokr Platform
        </span>
      </div>

      <AnimatePresence>
        {mobileNav ? (
          <>
            <motion.div
              className="fixed inset-0 z-40 bg-black/65 backdrop-blur-sm lg:hidden"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setMobileNav(false)}
              aria-hidden
            />
            <motion.aside
              initial={{ x: -288 }}
              animate={{ x: 0 }}
              exit={{ x: -288 }}
              transition={{ type: "spring", damping: 26, stiffness: 320 }}
              className={cn(
                "fixed inset-y-0 left-0 z-50 flex w-[min(288px,90vw)] flex-col border-r",
                isLight
                  ? "border-neutral-200 bg-white/98 backdrop-blur-xl lg:hidden"
                  : "border-neutral-900 bg-neutral-950/98 backdrop-blur-xl lg:hidden",
              )}
              id="mobile-nav"
            >
              <div
                className={cn(
                  "border-b px-4 py-3 text-[11px] font-bold uppercase tracking-widest",
                  isLight ? "border-neutral-100 text-neutral-500" : "border-neutral-900 text-neutral-500",
                )}
              >
                Navigate
              </div>
              <div className="flex-1 overflow-y-auto">{sidebar}</div>
            </motion.aside>
          </>
        ) : null}
      </AnimatePresence>

      <div className="relative mx-auto grid h-full min-h-0 lg:grid-cols-[272px_minmax(0,1fr)]">
        <aside
          className={cn(
            "hidden border-r backdrop-blur-xl lg:sticky lg:top-0 lg:flex lg:h-[100dvh] lg:shrink-0 lg:flex-col",
            isLight ? "border-neutral-200 bg-white lg:border-neutral-200" : "border-neutral-900/90 bg-neutral-950/85",
          )}
        >
          <div className="flex min-h-0 flex-1 flex-col px-4 py-6">{sidebar}</div>
        </aside>

        <div className="flex min-h-0 h-full flex-col">
          <header
            className={cn(
              "sticky top-0 z-20 backdrop-blur-xl",
              isLight
                ? "border-b border-neutral-200 bg-white/[0.88]"
                : "border-b border-neutral-900/80 bg-neutral-950/85",
            )}
          >
            {topNav}
          </header>

          <main
            className={cn(
              "relative flex min-h-0 flex-1 flex-col overflow-y-auto px-4 py-8 sm:px-6 lg:px-10",
              isLight ? "bg-[linear-gradient(180deg,#f9fafb_0%,transparent_28%)]" : "bg-[linear-gradient(180deg,rgba(10,10,10,0.2)_0%,transparent_32%)]",
            )}
          >
            {banners ? <div className="mx-auto mb-8 max-w-[1560px] space-y-4">{banners}</div> : null}
            <div className="mx-auto flex min-h-0 w-full max-w-[1560px] flex-1 flex-col">{children}</div>
          </main>
        </div>
      </div>
    </div>
  );
}
