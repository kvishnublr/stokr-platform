import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import { GlassPanel } from "./GlassPanel";

type Props = { children: ReactNode };
type State = { hasError: boolean; message?: string };

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(err: Error): State {
    return { hasError: true, message: err.message };
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Runtime surface error", error, info.componentStack);
  }

  override render() {
    if (!this.state.hasError) return this.props.children;

    return (
      <div className="flex min-h-[40vh] items-center justify-center p-8">
        <GlassPanel className="max-w-md p-8 text-center">
          <AlertTriangle className="mx-auto h-12 w-12 text-amber-400/90" />
          <div className="mt-4 text-lg font-semibold text-white">Workstation degraded</div>
          <p className="mt-2 text-sm text-neutral-400">
            {this.state.message ??
              "A client-side error occurred — refresh to reconnect data streams."}
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="mt-6 rounded-lg bg-white px-4 py-2 text-xs font-semibold text-neutral-950 hover:bg-neutral-200"
          >
            Reload workstation
          </button>
        </GlassPanel>
      </div>
    );
  }
}
