import { useState, useEffect } from "react";
import { cn } from "../../../lib/utils";
import { toast } from "sonner";

interface QueueStatus {
  name: string;
  pending: number;
  consumerCount: number;
  processingRate: number;
  avgMessageAge: number;
  deadLetterQueue: {
    name: string;
    pending: number;
  };
}

interface QueueHealthResponse {
  queues: QueueStatus[];
  totalPending: number;
  totalConsumers: number;
}

const getQueueHealth = (queue: QueueStatus) => {
  if (queue.pending > 5000) return "CRITICAL";
  if (queue.pending > 1000) return "WARNING";
  return "HEALTHY";
};

const getHealthColor = (health: string) => {
  switch (health) {
    case "CRITICAL":
      return "text-red-600 bg-red-50 border-red-200";
    case "WARNING":
      return "text-yellow-600 bg-yellow-50 border-yellow-200";
    default:
      return "text-green-600 bg-green-50 border-green-200";
  }
};

const getHealthIndicator = (health: string) => {
  switch (health) {
    case "CRITICAL":
      return "bg-red-500";
    case "WARNING":
      return "bg-yellow-500";
    default:
      return "bg-green-500";
  }
};

export function QueueMonitoringPanel() {
  const [queueHealth, setQueueHealth] = useState<QueueHealthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedQueue, setExpandedQueue] = useState<string | null>(null);

  const fetchQueueStatus = async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await fetch("/api/v1/admin/health/queues");
      if (!response.ok) throw new Error("Failed to fetch queue status");
      const data = await response.json();
      setQueueHealth(data);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unknown error";
      setError(message);
      toast.error("Failed to load queue status", { description: message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchQueueStatus();
    const interval = setInterval(fetchQueueStatus, 5000); // Refresh every 5 seconds
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <section className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Queue Monitoring</h3>
        <div className="rounded-lg bg-gray-50 p-8 text-center text-sm text-gray-500">
          Loading queue status...
        </div>
      </section>
    );
  }

  if (error || !queueHealth) {
    return (
      <section className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Queue Monitoring</h3>
        <div className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm">
          <div className="font-semibold text-amber-900">⚠️ DEMO MODE</div>
          <div className="mt-1 text-amber-800">Data is mocked for testing UI/UX. Real monitoring data available 2026-06-17</div>
        </div>
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Unable to load queue status: {error}
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-gray-200 bg-white p-6">
      <div className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm">
        <div className="font-semibold text-amber-900">⚠️ DEMO MODE</div>
        <div className="mt-1 text-amber-800">Data is mocked for testing UI/UX. Real monitoring data available 2026-06-17</div>
      </div>
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-bold text-gray-900">RabbitMQ Queue Status</h3>
        <div className="text-xs text-gray-500">
          Total Pending: <span className="font-bold text-gray-900">{queueHealth.totalPending}</span> •
          Consumers: <span className="font-bold text-gray-900">{queueHealth.totalConsumers}</span>
        </div>
      </div>

      <div className="space-y-2">
        {queueHealth.queues.map((queue) => {
          const health = getQueueHealth(queue);
          const isExpanded = expandedQueue === queue.name;
          const estimatedClearTime =
            queue.processingRate > 0
              ? Math.ceil(queue.pending / queue.processingRate)
              : queue.pending > 0
                ? "∞"
                : "0";

          return (
            <div
              key={queue.name}
              className={cn(
                "rounded-lg border p-4 cursor-pointer transition-all",
                getHealthColor(health)
              )}
              onClick={() =>
                setExpandedQueue(isExpanded ? null : queue.name)
              }
            >
              {/* Main Row */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className={cn("h-2 w-2 rounded-full", getHealthIndicator(health))} />
                  <div className="flex-1">
                    <div className="font-semibold text-gray-900">
                      {queue.name.replace(/\./g, " ").toUpperCase()}
                    </div>
                    <div className="text-xs text-gray-600">
                      {queue.pending} pending • {queue.processingRate} msg/sec
                    </div>
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-sm font-bold text-gray-900">
                    Est. {estimatedClearTime}s
                  </div>
                  <div className="text-xs text-gray-600">{health}</div>
                </div>
              </div>

              {/* Progress Bar */}
              <div className="mt-3 overflow-hidden rounded-full bg-gray-200">
                <div
                  className={cn(
                    "h-2 transition-all",
                    health === "CRITICAL"
                      ? "bg-red-500"
                      : health === "WARNING"
                        ? "bg-yellow-500"
                        : "bg-green-500"
                  )}
                  style={{
                    width: `${Math.min((queue.pending / 5000) * 100, 100)}%`,
                  }}
                />
              </div>

              {/* Expanded Details */}
              {isExpanded && (
                <div className="mt-4 space-y-2 border-t border-current pt-4 text-sm">
                  <div className="flex justify-between">
                    <span className="text-gray-600">Pending Messages:</span>
                    <span className="font-bold text-gray-900">{queue.pending}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Consumers:</span>
                    <span className="font-bold text-gray-900">{queue.consumerCount}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Processing Rate:</span>
                    <span className="font-bold text-gray-900">
                      {queue.processingRate} msg/sec
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-600">Avg Message Age:</span>
                    <span className="font-bold text-gray-900">
                      {queue.avgMessageAge.toFixed(2)}s
                    </span>
                  </div>

                  {/* Dead Letter Queue */}
                  {queue.deadLetterQueue.pending > 0 && (
                    <div className="mt-3 rounded-lg border border-current bg-opacity-20 p-3">
                      <div className="text-xs font-semibold text-red-700">
                        ⚠️ Dead Letter Queue Alert
                      </div>
                      <div className="mt-2 text-xs text-gray-700">
                        {queue.deadLetterQueue.pending} messages in{" "}
                        <span className="font-semibold">
                          {queue.deadLetterQueue.name}
                        </span>
                      </div>
                      <div className="mt-2">
                        <button className="rounded bg-red-600 px-2 py-1 text-xs font-semibold text-white hover:bg-red-700">
                          View DLQ
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
