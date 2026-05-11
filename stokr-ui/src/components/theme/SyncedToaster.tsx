import { Toaster } from "sonner";
import { useUiThemeStore } from "../../state/uiTheme";

export function SyncedToaster() {
  const mode = useUiThemeStore((s) => s.mode);
  return <Toaster richColors position="top-center" theme={mode} />;
}
