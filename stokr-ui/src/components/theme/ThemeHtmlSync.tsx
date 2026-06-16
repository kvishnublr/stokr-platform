import { useEffect } from "react";
import { useUiThemeStore } from "../../state/uiTheme";

export function ThemeHtmlSync() {
  const mode = useUiThemeStore((s) => s.mode);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.stokrTheme = mode;
    root.classList.toggle("dark", mode === "dark");
    root.style.colorScheme = mode;
    return () => {
      delete root.dataset.stokrTheme;
      root.classList.remove("dark");
      root.style.colorScheme = "";
    };
  }, [mode]);

  return null;
}
