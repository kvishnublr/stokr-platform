import { create } from "zustand";
import { persist } from "zustand/middleware";

type AdminWorkspaceState = {
  sidebarCollapsed: boolean;
  pinnedRoutes: string[];
  recentRoutes: string[];
  setSidebarCollapsed: (v: boolean) => void;
  toggleSidebar: () => void;
  pinRoute: (route: string) => void;
  unpinRoute: (route: string) => void;
  trackVisit: (route: string) => void;
};

const MAX_RECENT = 8;
const MAX_PINNED = 6;

export const useAdminWorkspaceStore = create<AdminWorkspaceState>()(
  persist(
    (set, get) => ({
      sidebarCollapsed: false,
      pinnedRoutes: ["/admin", "/admin/signals", "/admin/oms", "/admin/risk-dashboard"],
      recentRoutes: [],
      setSidebarCollapsed: (v) => set({ sidebarCollapsed: v }),
      toggleSidebar: () => set({ sidebarCollapsed: !get().sidebarCollapsed }),
      pinRoute: (route) =>
        set((s) => ({
          pinnedRoutes: s.pinnedRoutes.includes(route)
            ? s.pinnedRoutes
            : [route, ...s.pinnedRoutes].slice(0, MAX_PINNED),
        })),
      unpinRoute: (route) => set((s) => ({ pinnedRoutes: s.pinnedRoutes.filter((r) => r !== route) })),
      trackVisit: (route) =>
        set((s) => ({
          recentRoutes: [route, ...s.recentRoutes.filter((r) => r !== route)].slice(0, MAX_RECENT),
        })),
    }),
    { name: "stokr-admin-workspace-v1" },
  ),
);
