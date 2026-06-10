import React, { useState } from "react";
import { cn } from "../../lib/utils";

interface TimelineEvent {
  sequence: number;
  timestamp: string;
  service: string;
  event: string;
  status: "SUCCESS" | "PENDING" | "FAILED";
  durationMsFromPrevious: number;
  details: string;
  errorMessage?: string;
}

interface ExecutionTimeline {
  signalId: string;
  orderId?: string;
  symbol: string;
  side: string;
  quantity: number;
  events: TimelineEvent[];
  totalDurationMs: number;
  successful: boolean;
  failureReason?: string;
}

export function SignalLifecyclePanel() {
  const [signalId, setSignalId] = useState("");
  const [timeline, setTimeline] = useState<ExecutionTimeline | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSearch = async () => {
    if (!signalId.trim()) return;

    setLoading(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/v1/admin/signals/${encodeURIComponent(signalId)}/lifecycle`
      );
      if (!response.ok) {
        if (response.status === 404) {
          setError("Signal not found");
        } else {
          throw new Error("Failed to fetch signal lifecycle");
        }
        setTimeline(null);
      } else {
        const data = await response.json();
        setTimeline(data);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
      setTimeline(null);
    } finally {
      setLoading(false);
    }
  };

  const statusIcon = {
    SUCCESS: "✓",
    PENDING: "⏳",
    FAILED: "✗",
  };

  const statusColor = {
    SUCCESS: "text-green-600",
    PENDING: "text-yellow-600",
    FAILED: "text-red-600",
  };

  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-6">
      <h3 className="mb-4 text-lg font-bold text-gray-900">Signal Lifecycle</h3>

      <div className="mb-6 flex gap-2">
        <input
          type="text"
          placeholder="Enter Signal ID (e.g., sig-2026-06-10-001)"
          value={signalId}
          onChange={(e) => setSignalId(e.target.value)}
          onKeyPress={(e) => e.key === "Enter" && handleSearch()}
          className="flex-1 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm focus:border-orange-500 focus:outline-none"
        />
        <button
          onClick={handleSearch}
          disabled={loading || !signalId.trim()}
          className="rounded-lg bg-orange-500 px-4 py-2 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-50"
        >
          {loading ? "Loading..." : "Track"}
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-lg bg-red-50 border border-red-100 p-3 text-sm text-red-600">
          {error}
        </div>
      )}

      {timeline && (
        <div className="space-y-6">
          {/* Summary */}
          <div className={cn(
            "rounded-lg border p-4",
            timeline.successful
              ? "bg-green-50 border-green-100"
              : "bg-red-50 border-red-100"
          )}>
            <div className="flex items-start justify-between">
              <div>
                <div className="font-semibold text-gray-900">
                  {timeline.symbol} {timeline.side} {timeline.quantity}
                </div>
                <div className="mt-2 grid grid-cols-3 gap-4 text-sm">
                  <div>
                    <span className="text-gray-500">Signal ID:</span>
                    <span className="ml-2 font-mono text-gray-900">{timeline.signalId}</span>
                  </div>
                  {timeline.orderId && (
                    <div>
                      <span className="text-gray-500">Order ID:</span>
                      <span className="ml-2 font-mono text-gray-900">{timeline.orderId}</span>
                    </div>
                  )}
                  <div>
                    <span className="text-gray-500">Total Time:</span>
                    <span className="ml-2 font-bold text-gray-900">
                      {timeline.totalDurationMs}ms
                    </span>
                  </div>
                </div>
              </div>
              <span
                className={cn(
                  "text-2xl font-bold",
                  timeline.successful ? "text-green-600" : "text-red-600"
                )}
              >
                {timeline.successful ? "✓ SUCCESS" : "✗ FAILED"}
              </span>
            </div>
            {timeline.failureReason && (
              <div className="mt-3 text-sm text-red-600">
                <span className="font-semibold">Reason:</span> {timeline.failureReason}
              </div>
            )}
          </div>

          {/* Timeline */}
          <div className="space-y-3">
            <div className="text-sm font-semibold text-gray-900">Execution Timeline</div>
            {timeline.events.map((event, idx) => (
              <div key={idx} className="flex gap-4">
                {/* Timeline connector */}
                <div className="flex flex-col items-center">
                  <div className={cn(
                    "h-6 w-6 rounded-full flex items-center justify-center text-xs font-bold text-white",
                    event.status === "SUCCESS"
                      ? "bg-green-500"
                      : event.status === "PENDING"
                        ? "bg-yellow-500"
                        : "bg-red-500"
                  )}>
                    {statusIcon[event.status]}
                  </div>
                  {idx < timeline.events.length - 1 && (
                    <div className="w-0.5 h-12 bg-gray-200 my-1" />
                  )}
                </div>

                {/* Event details */}
                <div className="flex-1 pb-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <div className="font-semibold text-gray-900">{event.event}</div>
                      <div className="text-xs text-gray-500 mt-1">
                        Service: <span className="font-mono">{event.service}</span>
                      </div>
                      <div className="text-xs text-gray-600 mt-1">
                        {new Date(event.timestamp).toLocaleTimeString()}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className={cn(
                        "text-xs font-bold",
                        statusColor[event.status]
                      )}>
                        {event.status}
                      </div>
                      {event.durationMsFromPrevious > 0 && (
                        <div className="text-xs text-gray-500 mt-1">
                          +{event.durationMsFromPrevious}ms
                        </div>
                      )}
                    </div>
                  </div>

                  {event.details && (
                    <div className="mt-2 rounded-lg bg-gray-50 p-2 text-xs text-gray-700">
                      {event.details}
                    </div>
                  )}

                  {event.errorMessage && (
                    <div className="mt-2 rounded-lg bg-red-50 p-2 text-xs text-red-600">
                      <span className="font-semibold">Error:</span> {event.errorMessage}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* Performance Summary */}
          <div className="rounded-lg bg-gray-50 p-4 border border-gray-200">
            <div className="text-sm font-semibold text-gray-900 mb-3">Performance Breakdown</div>
            <div className="space-y-2 text-xs">
              {timeline.events
                .filter((e, i) => i > 0)
                .map((event, idx) => (
                  <div key={idx} className="flex items-center justify-between">
                    <span className="text-gray-600">
                      {timeline.events[idx].event} → {event.event}
                    </span>
                    <span className={cn(
                      "font-bold",
                      event.durationMsFromPrevious > 500
                        ? "text-red-600"
                        : event.durationMsFromPrevious > 200
                          ? "text-yellow-600"
                          : "text-green-600"
                    )}>
                      {event.durationMsFromPrevious}ms
                    </span>
                  </div>
                ))}
            </div>
          </div>
        </div>
      )}

      {!timeline && !error && !loading && (
        <div className="rounded-lg bg-gray-50 p-6 text-center">
          <div className="text-sm text-gray-500">
            Enter a signal ID above to track its lifecycle from generation to execution.
          </div>
        </div>
      )}
    </div>
  );
}
