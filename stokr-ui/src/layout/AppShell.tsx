import type { ReactNode } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useState } from "react";
import { Menu } from "lucide-react";
import { cn } from "../lib/utils";

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

  return (
    <div className="relative min-h-screen overflow-x-hidden bg-neutral-950">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-[420px] bg-[radial-gradient(ellipse_at_top,_rgba(59,130,246,0.11),transparent_58%),radial-gradient(ellipse_at_20%_0%,rgba(168,85,247,0.08),transparent_45%)]" />

      {/* Mobile rails */}
      <div className="sticky top-0 z-30 flex items-center gap-2 border-b border-neutral-900/80 bg-neutral-950/95 px-3 py-2 backdrop-blur-lg lg:hidden">
        <button
          type="button"
          aria-label="Open navigation"
          onClick={() => setMobileNav(true)}
          className="rounded-lg border border-neutral-800 p-2 text-neutral-200 hover:bg-neutral-900"
        >
          <Menu className="h-5 w-5" />
        </button>
        <span className="text-xs font-semibold tracking-wide text-neutral-400">Stokr workstation</span>
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
                "fixed inset-y-0 left-0 z-50 flex w-[min(288px,90vw)] flex-col border-r border-neutral-900",
                "bg-neutral-950/98 backdrop-blur-xl lg:hidden",
              )}
              id="mobile-nav"
            >
              <div className="border-b border-neutral-900 px-4 py-3 text-[11px] font-bold uppercase tracking-widest text-neutral-500">
                Navigate
              </div>
              <div className="flex-1 overflow-y-auto">{sidebar}</div>
            </motion.aside>
          </>
        ) : null}
      </AnimatePresence>

      <div className="relative mx-auto grid min-h-screen lg:grid-cols-[272px_minmax(0,1fr)]">
        {/* Desktop sidebar */}
        <aside className="hidden border-r border-neutral-900/90 bg-neutral-950/85 backdrop-blur-xl lg:flex lg:flex-col">
          <div className="px-4 py-6">{sidebar}</div>
        </aside>

        <div className="flex min-h-[100dvh] flex-col">
          <header className="sticky top-0 z-20 border-b border-neutral-900/80 bg-neutral-950/85 backdrop-blur-xl">
            {topNav}
          </header>

          <main className="relative flex-1 bg-[linear-gradient(180deg,rgba(10,10,10,0.2)_0%,transparent_32%)] px-4 py-8 sm:px-6 lg:px-10">
            {banners ? <div className="mx-auto mb-8 max-w-[1560px] space-y-4">{banners}</div> : null}
            <div className="mx-auto max-w-[1560px]">{children}</div>
          </main>
        </div>
      </div>
    </div>
  );
}
