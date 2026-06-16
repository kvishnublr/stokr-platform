import { create } from "zustand";
import { persist } from "zustand/middleware";

export type UiThemeMode = "light" | "dark";

type UiThemeState = {
  mode: UiThemeMode;
  toggle: () => void;
  setMode: (m: UiThemeMode) => void;
};

export const useUiThemeStore = create<UiThemeState>()(
  persist(
    (set, get) => ({
      mode: "light",
      toggle: () => set({ mode: get().mode === "light" ? "dark" : "light" }),
      setMode: (m) => set({ mode: m }),
    }),
    { name: "stokr-ui-theme" },
  ),
);
