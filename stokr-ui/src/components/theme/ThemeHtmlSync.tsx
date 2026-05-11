import { useEffect } from "react";
import { useUiThemeStore } from "../../state/uiTheme";

export function ThemeHtmlSync() {
  const mode = useUiThemeStore((s) => s.mode);

  useEffect(() => {
    document.documentElement.dataset.stokrTheme = mode;
    return () => {
      delete document.documentElement.dataset.stokrTheme;
    };
  }, [mode]);

  return null;
}
