import { useState } from "react";
import { cn } from "../../../lib/utils";
import { fmtTime } from "../../../lib/dateUtils";
import { toast } from "sonner";
import { api } from "../../../api/client";

interface ExecutionEvent {
  timestamp: number;
  service: string;
  event: string;
  deltaMs: number;
  details?: Record<string, unknown>;
}

interface SignalLifecycleData {
  signalId: string;
  symbol: string;
  side: string;
  quantity: number;
  aiScore: number;
  status: "GENERATED" | "QUEUED" | "PROCESSING" | "FILLED" | "REJECTED" | "ERROR";
  events: ExecutionEvent[];
  totalLatencyMs: number;
  orderId?: string;
}

const eventColors: Record<string, string> = {
  SIGNAL_GENERATED: "bg-blue-50 border-blue-200",
  SIGNAL_QUEUED: "bg-purple-50 border-purple-200",
  SIGNAL_RECEIVED: "bg-indigo-50 border-indigo-200",
  RISK_VALIDATION_START: "bg-orange-50 border-orange-200",
  RISK_VALIDATION_PASSED: "bg-green-50 border-green-200",
  RISK_VALIDATION_FAILED: "bg-red-50 border-red-200",
  BROKER_SUBMISSION: "bg-cyan-50 border-cyan-200",
  ORDER_FILLED: "bg-green-50 border-green-200",
  ORDER_ERROR: "bg-red-50 border-red-200",
  POSITION_CREATED: "bg-emerald-50 border-emerald-200",
};

const eventIcons: Record<string, string> = {
  SIGNAL_GENERATED: "📊",
  SIGNAL_QUEUED: "📮",
  SIGNAL_RECEIVED: "📨",
  RISK_VALIDATION_START: "🔍",
  RISK_VALIDATION_PASSED: "✅",
  RISK_VALIDATION_FAILED: "❌",
  BROKER_SUBMISSION: "📤",
  ORDER_FILLED: "✓",
  ORDER_ERROR: "⚠️",
  POSITION_CREATED: "📍",
};

export function SignalLifecyclePanel() {
  const [signalId, setSignalId] = useState("");
  const [lifecycle, setLifecycle] = useState<SignalLifecycleData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);

  const handleSearch = async () => {
    if (!signalId.trim()) {
      toast.error("Please enter a signal ID");
      return;
    }

    try {
      setLoading(true);
      setError(null);
      setSearched(true);
      const { data } = await api.get(`/api/v1/admin/signals/${signalId}/lifecycle`);
      const payload = data?.data ?? data;
      setLifecycle(payload?.signalId ? payload : null);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unknown error";
      setError(message);
      setLifecycle(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="rounded-2xl border border-gray-200 bg-white p-6">
      <h3 className="mb-4 text-lg font-bold text-gray-900">Signal Lifecycle Tracking</h3>

      {/* Search Input */}
      <div className="mb-6 flex gap-2">
        <input
          type="text"
          placeholder="Enter signal ID (e.g., sig-2026-06-10-001)"
          value={signalId}
          onChange={(e) => setSignalId(e.target.value)}
          onKeyPress={(e) => e.key === "Enter" && handleSearch()}
          className="flex-1 rounded-lg border border-gray-200 px-4 py-2 text-sm focus:border-blue-500 focus:outline-none"
        />
        <button
          onClick={handleSearch}
          disabled={loading}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {loading ? "Searching..." : "Search"}
        </button>
      </div>

      {/* Error State */}
      {error && searched && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* No Results State */}
      {!lifecycle && searched && !error && !loading && (
        <div className="rounded-lg bg-gray-50 p-8 text-center text-sm text-gray-500">
          No signal lifecycle data available
        </div>
      )}

      {/* Results */}
      {lifecycle && (
        <div className="space-y-4">
          {/* Signal Summary */}
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <div>
                <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Signal ID
                </div>
                <div className="mt-1 font-mono text-sm font-bold text-gray-900">
                  {lifecycle.signalId}
                </div>
              </div>
              <div>
                <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Symbol
                </div>
                <div className="mt-1 text-lg font-bold text-gray-900">
                  {lifecycle.symbol} {lifecycle.side}
                </div>
              </div>
              <div>
                <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Qty & AI Score
                </div>
                <div className="mt-1 text-sm font-bold text-gray-900">
                  {lifecycle.quantity} units @ {lifecycle.aiScore}%
                </div>
              </div>
              <div>
                <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                  Status
                </div>
                <div className={cn(
                  "mt-1 inline-block rounded-full px-2.5 py-0.5 text-xs font-bold",
                  lifecycle.status === "FILLED"
                    ? "bg-green-100 text-green-800"
                    : lifecycle.status === "ERROR"
                      ? "bg-red-100 text-red-800"
                      : lifecycle.status === "REJECTED"
                        ? "bg-orange-100 text-orange-800"
                        : "bg-blue-100 text-blue-800"
                )}>
                  {lifecycle.status}
                </div>
              </div>
            </div>
          </div>

          {/* Execution Timeline */}
          <div>
            <h4 className="mb-3 text-sm font-bold text-gray-900">
              Execution Timeline ({lifecycle.totalLatencyMs}ms total)
            </h4>
            <div className="space-y-2">
              {(lifecycle.events ?? []).map((event, idx) => (
                <div
                  key={idx}
                  className={cn(
                    "rounded-lg border p-3",
                    eventColors[event.event] || "bg-gray-50 border-gray-200"
                  )}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex items-start gap-3">
                      <span className="text-xl">
                        {eventIcons[event.event] || "•"}
                      </span>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-semibold text-gray-900">
                            {event.event.replace(/_/g, " ")}
                          </span>
                          <span className="rounded-full bg-gray-200 px-2 py-0.5 text-xs text-gray-700">
                            {event.service}
                          </span>
                        </div>
                        <div className="mt-1 text-xs text-gray-600">
                          {fmtTime(event.timestamp)}
                        </div>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="font-bold text-gray-900">
                        +{event.deltaMs}ms
                      </div>
                      {idx > 0 && (
                        <div className="text-xs text-gray-500">
                          from previous
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Latency Breakdown */}
          <div>
            <h4 className="mb-3 text-sm font-bold text-gray-900">
              Latency Breakdown
            </h4>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              {[
                {
                  label: "Signal Gen",
                  value:
                    lifecycle.events[1]?.deltaMs -
                      lifecycle.events[0]?.deltaMs || 0,
                },
                {
                  label: "Risk Check",
                  value: lifecycle.events.find(
                    (e) => e.event === "RISK_VALIDATION_PASSED"
                  )?.deltaMs || 0,
                },
                {
                  label: "Broker Submit",
                  value: lifecycle.events.find(
                    (e) => e.event === "BROKER_SUBMISSION"
                  )?.deltaMs || 0,
                },
                {
                  label: "Order Fill",
                  value: lifecycle.events.find(
                    (e) => e.event === "ORDER_FILLED"
                  )?.deltaMs || 0,
                },
              ].map(({ label, value }) => (
                <div key={label} className="rounded-lg bg-gray-50 p-3">
                  <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                    {label}
                  </div>
                  <div className="mt-1 text-lg font-bold text-gray-900">
                    {value}ms
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Order ID */}
          {lifecycle.orderId && (
            <div className="rounded-lg border border-green-200 bg-green-50 p-4">
              <div className="text-xs font-semibold uppercase tracking-wide text-green-700">
                Order Placed Successfully
              </div>
              <div className="mt-2 font-mono text-sm font-bold text-green-900">
                Order ID: {lifecycle.orderId}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Empty State */}
      {!searched && !lifecycle && (
        <div className="rounded-lg bg-gray-50 p-8 text-center">
          <div className="text-sm text-gray-600">
            Search for a signal ID to view its execution timeline
          </div>
        </div>
      )}
    </section>
  );
}
